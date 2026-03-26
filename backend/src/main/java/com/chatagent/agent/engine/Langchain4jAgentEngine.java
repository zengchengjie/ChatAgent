package com.chatagent.agent.engine;

import com.chatagent.agent.dto.AgentChatResponse;
import com.chatagent.agent.dto.AgentStepResponse;
import com.chatagent.chat.ChatService;
import com.chatagent.chat.MessageRole;
import com.chatagent.chat.dto.MessageResponse;
import com.chatagent.common.ApiException;
import com.chatagent.config.AgentProperties;
import com.chatagent.config.DashScopeProperties;
import com.chatagent.tools.ToolRegistry;
import com.fasterxml.jackson.databind.ObjectMapper;
import dev.langchain4j.agent.tool.Tool;
import dev.langchain4j.model.openai.OpenAiChatModel;
import dev.langchain4j.service.AiServices;
import java.io.IOException;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.Callable;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.slf4j.MDC;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

@Service
@RequiredArgsConstructor
@Slf4j
public class Langchain4jAgentEngine implements AgentEngine {

    private static final String SYSTEM_PROMPT =
            "You are a helpful assistant for this app. For complex questions, provide a concise 1-3 step plan, "
                    + "then use tools when needed, and finally summarize conclusion with evidence sources.";
    private static final Pattern PLAN_LINE_PATTERN = Pattern.compile("^(?:\\d+[.)]|[-*])\\s+(.*)$");

    private final ChatService chatService;
    private final DashScopeProperties dashScopeProperties;
    private final AgentProperties agentProperties;
    private final ToolRegistry toolRegistry;
    private final ObjectMapper objectMapper;

    private final ExecutorService toolPool = Executors.newCachedThreadPool();
    private final ThreadLocal<ToolCallContext> toolContextLocal = new ThreadLocal<>();

    @Override
    public String name() {
        return "langchain4j";
    }

    @Override
    public AgentChatResponse chatSync(Long userId, String sessionId, String userContent) {
        String traceId = UUID.randomUUID().toString();
        MDC.put("traceId", traceId);
        int rounds = 1;
        boolean maxStepsHit = false;
        ToolCallContext ctx = new ToolCallContext(userId, sessionId, traceId, null);
        toolContextLocal.set(ctx);
        try {
            chatService.appendMessage(userId, sessionId, MessageRole.USER, userContent, null, null);
            String reply = assistant().chat(buildPromptWithHistory(userId, sessionId, userContent));
            chatService.appendMessage(userId, sessionId, MessageRole.ASSISTANT, reply, null, null);

            List<AgentStepResponse> steps = new ArrayList<>();
            steps.addAll(toPlanSteps(reply));
            steps.addAll(ctx.steps);
            return AgentChatResponse.builder().reply(reply).steps(steps).build();
        } finally {
            log.info(
                    "event=agent_summary traceId={} rounds={} toolCalls={} ragCalls={} maxStepsHit={}",
                    traceId,
                    rounds,
                    ctx.toolCallsTotal,
                    ctx.ragCalls,
                    maxStepsHit);
            toolContextLocal.remove();
            MDC.remove("traceId");
        }
    }

    @Override
    public void chatStream(Long userId, String sessionId, String userContent, SseEmitter emitter) {
        String traceId = UUID.randomUUID().toString();
        MDC.put("traceId", traceId);
        int rounds = 1;
        boolean maxStepsHit = false;
        ToolCallContext ctx = new ToolCallContext(userId, sessionId, traceId, emitter);
        toolContextLocal.set(ctx);
        try {
            chatService.appendMessage(userId, sessionId, MessageRole.USER, userContent, null, null);
            String reply = assistant().chat(buildPromptWithHistory(userId, sessionId, userContent));
            chatService.appendMessage(userId, sessionId, MessageRole.ASSISTANT, reply, null, null);

            emitPlanEventsSafe(emitter, reply, traceId);
            emitTextDeltas(emitter, reply);
            sendJson(emitter, "done", Map.of("ok", true));
        } catch (ApiException e) {
            sendJson(emitter, "error", Map.of("message", e.getMessage()));
        } catch (Exception e) {
            log.error("event=agent_stream_failed traceId={}", traceId, e);
            sendJson(emitter, "error", Map.of("message", "Internal error"));
        } finally {
            log.info(
                    "event=agent_summary traceId={} rounds={} toolCalls={} ragCalls={} maxStepsHit={}",
                    traceId,
                    rounds,
                    ctx.toolCallsTotal,
                    ctx.ragCalls,
                    maxStepsHit);
            toolContextLocal.remove();
            MDC.remove("traceId");
            emitter.complete();
        }
    }

    private Assistant assistant() {
        OpenAiChatModel model =
                OpenAiChatModel.builder()
                        .apiKey(dashScopeProperties.getApiKey())
                        .baseUrl(dashScopeProperties.getBaseUrl())
                        .modelName(dashScopeProperties.getModel())
                        .temperature((double) dashScopeProperties.getTemperature())
                        .timeout(Duration.ofMillis(dashScopeProperties.getReadTimeoutMs()))
                        .build();
        return AiServices.builder(Assistant.class)
                .chatLanguageModel(model)
                .tools(new Lc4jTools())
                .build();
    }

    private String buildPromptWithHistory(Long userId, String sessionId, String userContent) {
        StringBuilder sb = new StringBuilder();
        sb.append(SYSTEM_PROMPT).append("\n\nConversation history:\n");
        List<MessageResponse> rows = chatService.listMessages(userId, sessionId);
        for (MessageResponse r : rows) {
            if (r.getRole() == MessageRole.SYSTEM) {
                continue;
            }
            sb.append("- ").append(r.getRole().name()).append(": ").append(r.getContent() == null ? "" : r.getContent()).append("\n");
        }
        sb.append("\nCurrent user message:\n").append(userContent);
        return sb.toString();
    }

    private String executeToolGuarded(String toolName, String argsJson) throws Exception {
        ToolCallContext ctx = toolContextLocal.get();
        if (ctx == null) {
            return toolRegistry.execute(toolName, argsJson, "no-trace");
        }

        if (ctx.toolCallsThisTurn >= agentProperties.getMaxToolCallsPerTurn()) {
            emitGuardrail(ctx, "maxToolCallsPerTurn", agentProperties.getMaxToolCallsPerTurn(), ctx.toolCallsThisTurn + 1);
            throw new ApiException(HttpStatus.BAD_REQUEST, "Guardrail hit: max tool calls per turn reached");
        }
        if (ctx.toolCallsTotal >= agentProperties.getMaxToolCallsTotal()) {
            emitGuardrail(ctx, "maxToolCallsTotal", agentProperties.getMaxToolCallsTotal(), ctx.toolCallsTotal + 1);
            throw new ApiException(HttpStatus.BAD_REQUEST, "Guardrail hit: max tool calls total reached");
        }

        sendJsonSafe(ctx.emitter, "tool_start", Map.of("name", toolName, "id", ""));
        long t1 = System.currentTimeMillis();
        String result;
        try {
            Future<String> f =
                    toolPool.submit((Callable<String>) () -> toolRegistry.execute(toolName, argsJson, ctx.traceId));
            try {
                result = f.get(agentProperties.getToolTimeoutMs(), TimeUnit.MILLISECONDS);
            } catch (TimeoutException te) {
                f.cancel(true);
                emitGuardrail(
                        ctx,
                        "toolTimeoutMs",
                        (int) agentProperties.getToolTimeoutMs(),
                        (int) agentProperties.getToolTimeoutMs());
                throw te;
            } catch (ExecutionException ee) {
                Throwable c = ee.getCause();
                if (c instanceof Exception e) {
                    throw e;
                }
                throw new RuntimeException(c);
            }
            log.info(
                    "event=tool_call traceId={} tool={} ms={} ok=true",
                    ctx.traceId,
                    toolName,
                    System.currentTimeMillis() - t1);
        } catch (TimeoutException te) {
            result = "Error: tool timed out";
            log.warn(
                    "event=tool_call traceId={} tool={} ms={} ok=false err={}",
                    ctx.traceId,
                    toolName,
                    System.currentTimeMillis() - t1,
                    te.toString());
        } catch (Exception e) {
            result = "Error: " + e.getMessage();
            log.warn(
                    "event=tool_call traceId={} tool={} ms={} ok=false err={}",
                    ctx.traceId,
                    toolName,
                    System.currentTimeMillis() - t1,
                    e.toString());
        }

        ctx.toolCallsThisTurn++;
        ctx.toolCallsTotal++;
        if ("search_knowledge".equals(toolName)) {
            ctx.ragCalls++;
        }
        ctx.steps.add(
                AgentStepResponse.builder()
                        .type("tool")
                        .toolName(toolName)
                        .detail(truncate(result, 500))
                        .build());
        chatService.appendMessage(ctx.userId, ctx.sessionId, MessageRole.TOOL, result, null, null);
        sendJsonSafe(
                ctx.emitter,
                "tool_end",
                Map.of("name", toolName, "ok", true, "detail", truncate(result, 500)));
        return result;
    }

    private void emitGuardrail(ToolCallContext ctx, String reason, int limit, int actual) {
        log.warn(
                "event=guardrail_hit traceId={} reason={} limit={} actual={}",
                ctx.traceId,
                reason,
                limit,
                actual);
        sendJsonSafe(ctx.emitter, "guardrail", Map.of("reason", reason, "limit", limit, "actual", actual));
    }

    private static String truncate(String s, int max) {
        if (s == null) {
            return "";
        }
        return s.length() <= max ? s : s.substring(0, max) + "...";
    }

    private static void sendJson(SseEmitter emitter, String event, Map<String, ?> data) {
        try {
            emitter.send(SseEmitter.event().name(event).data(data));
        } catch (IOException e) {
            throw new RuntimeException(e);
        }
    }

    private static void sendJsonSafe(SseEmitter emitter, String event, Map<String, ?> data) {
        if (emitter == null) {
            return;
        }
        try {
            emitter.send(SseEmitter.event().name(event).data(data));
        } catch (Exception ignored) {
            // degrade only
        }
    }

    private boolean emitPlanEventsSafe(SseEmitter emitter, String assistantContent, String traceId) {
        try {
            List<String> planSteps = extractPlanSteps(assistantContent);
            if (planSteps.isEmpty()) {
                return false;
            }
            sendJson(emitter, "plan_start", Map.of("count", planSteps.size()));
            for (int i = 0; i < planSteps.size(); i++) {
                sendJson(
                        emitter,
                        "plan_step",
                        Map.of("stepIndex", i + 1, "text", truncate(planSteps.get(i), 300)));
            }
            sendJson(emitter, "plan_done", Map.of("ok", true));
            return true;
        } catch (Exception e) {
            log.warn("event=plan_emit_failed traceId={} err={}", traceId, e.toString());
            return false;
        }
    }

    private static List<AgentStepResponse> toPlanSteps(String assistantContent) {
        List<String> plan = extractPlanSteps(assistantContent);
        List<AgentStepResponse> steps = new ArrayList<>();
        for (int i = 0; i < plan.size(); i++) {
            steps.add(AgentStepResponse.builder().type("plan").stepIndex(i + 1).detail(plan.get(i)).build());
        }
        return steps;
    }

    private static List<String> extractPlanSteps(String assistantContent) {
        if (assistantContent == null || assistantContent.isBlank()) {
            return List.of();
        }
        List<String> out = new ArrayList<>();
        for (String raw : assistantContent.split("\\r?\\n")) {
            String line = raw.trim();
            if (line.isEmpty()) {
                continue;
            }
            Matcher m = PLAN_LINE_PATTERN.matcher(line);
            if (m.matches()) {
                String step = m.group(1).trim();
                if (!step.isEmpty()) {
                    out.add(step);
                }
            }
            if (out.size() >= 3) {
                break;
            }
        }
        return out;
    }

    private static void emitTextDeltas(SseEmitter emitter, String text) {
        if (text == null || text.isEmpty()) {
            return;
        }
        final int chunk = 32;
        for (int i = 0; i < text.length(); i += chunk) {
            String part = text.substring(i, Math.min(text.length(), i + chunk));
            sendJson(emitter, "delta", Map.of("text", part));
        }
    }

    interface Assistant {
        String chat(String userMessage);
    }

    class Lc4jTools {
        @Tool("Evaluate a numeric arithmetic expression.")
        public String calculator(String expression) throws Exception {
            String argsJson = objectMapper.writeValueAsString(Map.of("expression", expression == null ? "" : expression));
            return executeToolGuarded("calculator", argsJson);
        }

        @Tool("Return mock weather for a city.")
        public String getMockWeather(String city) throws Exception {
            String argsJson = objectMapper.writeValueAsString(Map.of("city", city == null ? "" : city));
            return executeToolGuarded("get_mock_weather", argsJson);
        }

        @Tool("Search local markdown knowledge base.")
        public String searchKnowledge(
                String query, Integer k, Double minScore, String docTitleFilter) throws Exception {
            var m = new java.util.LinkedHashMap<String, Object>();
            m.put("query", query == null ? "" : query);
            if (k != null) {
                m.put("k", k);
            }
            if (minScore != null) {
                m.put("minScore", minScore);
            }
            if (docTitleFilter != null && !docTitleFilter.isBlank()) {
                m.put("docTitleFilter", docTitleFilter);
            }
            String argsJson = objectMapper.writeValueAsString(m);
            return executeToolGuarded("search_knowledge", argsJson);
        }
    }

    static class ToolCallContext {
        final Long userId;
        final String sessionId;
        final String traceId;
        final SseEmitter emitter;
        int toolCallsThisTurn = 0;
        int toolCallsTotal = 0;
        int ragCalls = 0;
        final List<AgentStepResponse> steps = new ArrayList<>();

        ToolCallContext(Long userId, String sessionId, String traceId, SseEmitter emitter) {
            this.userId = userId;
            this.sessionId = sessionId;
            this.traceId = traceId;
            this.emitter = emitter;
        }
    }
}


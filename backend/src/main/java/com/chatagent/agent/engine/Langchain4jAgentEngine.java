package com.chatagent.agent.engine;

import com.chatagent.agent.audit.AgentRunAudit;
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
import dev.langchain4j.model.openai.OpenAiStreamingChatModel;
import dev.langchain4j.service.AiServices;
import dev.langchain4j.service.TokenStream;
import java.io.IOException;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.Callable;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicReference;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import io.micrometer.core.instrument.MeterRegistry;
import org.slf4j.MDC;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

@Service
@RequiredArgsConstructor
@Slf4j
public class Langchain4jAgentEngine implements AgentEngine {

    private static final String SYSTEM_PROMPT =
            "You are a helpful assistant for this app.\n"
                    + "\n"
                    + "Rules:\n"
                    + "- For complex questions, first provide a concise 1-3 step plan.\n"
                    + "- After the plan, you MUST execute it. Do NOT stop after planning.\n"
                    + "- When the user asks for weather, you MUST call get_mock_weather for each city.\n"
                    + "  - Tool input city should be an English/pinyin key when possible: beijing/shanghai/hangzhou/chengdu/shenzhen/guangzhou.\n"
                    + "- When the user asks for arithmetic, you MUST call calculator(expression).\n"
                    + "- In the final answer, summarize results and cite evidence sources (tool outputs).\n"
                    + "- If a tool fails, explain the failure and continue with what you can.";
    private static final Pattern PLAN_LINE_PATTERN = Pattern.compile("^(?:\\d+[.)]|[-*])\\s+(.*)$");
    private static final Pattern SIMPLE_EXPR_PATTERN =
            Pattern.compile("(\\d+\\s*(?:[+\\-*/])\\s*\\d+)");

    private final ChatService chatService;
    private final DashScopeProperties dashScopeProperties;
    private final AgentProperties agentProperties;
    private final ToolRegistry toolRegistry;
    private final ObjectMapper objectMapper;
    private final MeterRegistry meterRegistry;

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
        ToolCallContext ctx = new ToolCallContext(userId, sessionId, traceId, null);
        ctx.audit.onRound();
        toolContextLocal.set(ctx);
        try {
            chatService.appendMessage(userId, sessionId, MessageRole.USER, userContent, null, null);
            String model = chatService.findSessionModel(userId, sessionId).orElse(null);
            // Deterministic fallback: if tool calling doesn't happen, still execute obvious tools.
            maybeExecuteDeterministicToolsSafe(userContent);
            String reply = assistant(model).chat(buildPromptWithHistory(userId, sessionId, userContent));
            chatService.appendMessage(userId, sessionId, MessageRole.ASSISTANT, reply, null, null);

            List<AgentStepResponse> steps = new ArrayList<>();
            steps.addAll(toPlanSteps(reply));
            steps.addAll(ctx.steps);
            return AgentChatResponse.builder().reply(reply).steps(steps).build();
        } finally {
            log.info(
                    "event=agent_summary traceId={} rounds={} toolCalls={} ragCalls={} maxStepsHit={}",
                    traceId,
                    ctx.audit.rounds(),
                    ctx.audit.toolCalls(),
                    ctx.audit.ragCalls(),
                    ctx.audit.maxStepsHit());
            meterRegistry.counter("chatagent.agent.summary", "engine", "langchain4j").increment();
            toolContextLocal.remove();
            MDC.remove("traceId");
        }
    }

    @Override
    public void chatStream(Long userId, String sessionId, String userContent, SseEmitter emitter) {
        String traceId = UUID.randomUUID().toString();
        MDC.put("traceId", traceId);
        ToolCallContext ctx = new ToolCallContext(userId, sessionId, traceId, emitter);
        ctx.audit.onRound();
        toolContextLocal.set(ctx);
        try {
            chatService.appendMessage(userId, sessionId, MessageRole.USER, userContent, null, null);
            String model = chatService.findSessionModel(userId, sessionId).orElse(null);
            // Deterministic fallback: if tool calling doesn't happen, still execute obvious tools.
            maybeExecuteDeterministicToolsSafe(userContent);
            String reply;
            if (agentProperties.isLangchainTokenStreamingEnabled()) {
                reply = streamWithLangchainModel(userId, sessionId, userContent, emitter, traceId, model);
            } else {
                reply = assistant(model).chat(buildPromptWithHistory(userId, sessionId, userContent));
                emitTextDeltas(emitter, reply);
            }
            chatService.appendMessage(userId, sessionId, MessageRole.ASSISTANT, reply, null, null);

            emitPlanEventsSafe(emitter, reply, traceId);
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
                    ctx.audit.rounds(),
                    ctx.audit.toolCalls(),
                    ctx.audit.ragCalls(),
                    ctx.audit.maxStepsHit());
            meterRegistry.counter("chatagent.agent.summary", "engine", "langchain4j").increment();
            toolContextLocal.remove();
            MDC.remove("traceId");
            emitter.complete();
        }
    }

    private Assistant assistant(String modelOverride) {
        OpenAiChatModel model =
                OpenAiChatModel.builder()
                        .apiKey(dashScopeProperties.getApiKey())
                        .baseUrl(dashScopeProperties.getBaseUrl())
                        .modelName(
                                modelOverride != null && !modelOverride.isBlank()
                                        ? modelOverride
                                        : dashScopeProperties.getModel())
                        .temperature((double) dashScopeProperties.getTemperature())
                        .timeout(Duration.ofMillis(dashScopeProperties.getReadTimeoutMs()))
                        .build();
        return AiServices.builder(Assistant.class)
                .chatLanguageModel(model)
                .tools(new Lc4jTools())
                .build();
    }

    private StreamingAssistant streamingAssistant(String modelOverride) {
        OpenAiStreamingChatModel model =
                OpenAiStreamingChatModel.builder()
                        .apiKey(dashScopeProperties.getApiKey())
                        .baseUrl(dashScopeProperties.getBaseUrl())
                        .modelName(
                                modelOverride != null && !modelOverride.isBlank()
                                        ? modelOverride
                                        : dashScopeProperties.getModel())
                        .temperature((double) dashScopeProperties.getTemperature())
                        .timeout(Duration.ofMillis(dashScopeProperties.getReadTimeoutMs()))
                        .build();
        return AiServices.builder(StreamingAssistant.class)
                .streamingChatLanguageModel(model)
                .tools(new Lc4jTools())
                .build();
    }

    private String streamWithLangchainModel(
            Long userId,
            String sessionId,
            String userContent,
            SseEmitter emitter,
            String traceId,
            String modelOverride) {
        String prompt = buildPromptWithHistory(userId, sessionId, userContent);
        StringBuilder fullText = new StringBuilder();
        CountDownLatch done = new CountDownLatch(1);
        AtomicReference<Throwable> errorRef = new AtomicReference<>();
        try {
            TokenStream tokenStream = streamingAssistant(modelOverride).chat(prompt);
            tokenStream.onNext(
                    delta -> {
                        if (delta == null || delta.isEmpty()) {
                            return;
                        }
                        fullText.append(delta);
                        sendJsonSafe(emitter, "delta", Map.of("text", delta));
                    });
            tokenStream.onComplete(ignored -> done.countDown());
            tokenStream.onError(
                    e -> {
                        errorRef.set(e);
                        done.countDown();
                    });
            tokenStream.start();
            boolean completed = done.await(dashScopeProperties.getReadTimeoutMs() + 10_000L, TimeUnit.MILLISECONDS);
            if (!completed) {
                throw new RuntimeException("langchain streaming timeout");
            }
            if (errorRef.get() != null) {
                throw new RuntimeException(errorRef.get());
            }
            return fullText.toString();
        } catch (Exception e) {
            log.warn("event=langchain_stream_fallback traceId={} err={}", traceId, e.toString());
            String reply = assistant(modelOverride).chat(prompt);
            emitTextDeltas(emitter, reply);
            return reply;
        }
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

    private void maybeExecuteDeterministicToolsSafe(String userContent) {
        try {
            DeterministicToolResult r = maybeExecuteDeterministicTools(userContent);
            if (r.weatherCalls > 0 || r.calculatorCalls > 0) {
                log.info(
                        "event=deterministic_tool_fallback weatherCalls={} calculatorCalls={} cities={} expression={}",
                        r.weatherCalls,
                        r.calculatorCalls,
                        r.cities,
                        r.expression);
            }
        } catch (ApiException e) {
            // keep behavior: let ApiException bubble (guardrails), but don't break compilation signature
            throw e;
        } catch (Exception e) {
            log.warn("event=deterministic_tool_fallback_failed err={}", e.toString());
        }
    }

    private DeterministicToolResult maybeExecuteDeterministicTools(String userContent) throws Exception {
        DeterministicToolResult r = new DeterministicToolResult();
        if (userContent == null || userContent.isBlank()) {
            return r;
        }
        String text = userContent.trim();

        // Weather: if user mentions weather and known cities, call weather tool for each city.
        boolean wantsWeather = text.contains("天气") || text.toLowerCase().contains("weather");
        List<String> cities = extractCities(text);
        r.cities = cities;
        if (wantsWeather && !cities.isEmpty()) {
            for (String city : cities) {
                String argsJson = objectMapper.writeValueAsString(Map.of("city", city));
                executeToolGuarded("get_mock_weather", argsJson);
                r.weatherCalls++;
            }
        }

        // Calculator: detect simple "a+b" style expression.
        Matcher m = SIMPLE_EXPR_PATTERN.matcher(text.replace(" ", ""));
        if (m.find()) {
            String expr = m.toMatchResult().group(1);
            r.expression = expr;
            String argsJson = objectMapper.writeValueAsString(Map.of("expression", expr));
            executeToolGuarded("calculator", argsJson);
            r.calculatorCalls++;
        }
        return r;
    }

    private static List<String> extractCities(String text) {
        // Keep order and de-duplicate
        java.util.LinkedHashSet<String> out = new java.util.LinkedHashSet<>();
        // Chinese names
        if (text.contains("北京")) out.add("北京");
        if (text.contains("上海")) out.add("上海");
        if (text.contains("杭州")) out.add("杭州");
        if (text.contains("成都")) out.add("成都");
        if (text.contains("深圳")) out.add("深圳");
        if (text.contains("广州")) out.add("广州");
        // pinyin/english keys
        String low = text.toLowerCase();
        if (low.contains("beijing")) out.add("beijing");
        if (low.contains("shanghai")) out.add("shanghai");
        if (low.contains("hangzhou")) out.add("hangzhou");
        if (low.contains("chengdu")) out.add("chengdu");
        if (low.contains("shenzhen")) out.add("shenzhen");
        if (low.contains("guangzhou")) out.add("guangzhou");
        return new java.util.ArrayList<>(out);
    }

    private static class DeterministicToolResult {
        int weatherCalls = 0;
        int calculatorCalls = 0;
        List<String> cities = List.of();
        String expression = null;
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
        int perToolLimit = agentProperties.getMaxToolCallsPerTool();
        int currentForTool = ctx.toolCallsByName.getOrDefault(toolName, 0);
        if (currentForTool >= perToolLimit) {
            emitGuardrail(ctx, "maxToolCallsPerTool", perToolLimit, currentForTool + 1);
            throw new ApiException(HttpStatus.BAD_REQUEST, "Guardrail hit: max tool calls per tool reached");
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
        ctx.audit.onToolCall(toolName);
        ctx.toolCallsByName.merge(toolName, 1, Integer::sum);
        meterRegistry.counter("chatagent.tool.calls", "tool", toolName, "engine", "langchain4j").increment();
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
        meterRegistry.counter("chatagent.guardrail.hit", "reason", reason, "engine", "langchain4j").increment();
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

    interface StreamingAssistant {
        TokenStream chat(String userMessage);
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
        final AgentRunAudit audit;
        int toolCallsThisTurn = 0;
        int toolCallsTotal = 0;
        int ragCalls = 0;
        final java.util.Map<String, Integer> toolCallsByName = new java.util.HashMap<>();
        final List<AgentStepResponse> steps = new ArrayList<>();

        ToolCallContext(Long userId, String sessionId, String traceId, SseEmitter emitter) {
            this.userId = userId;
            this.sessionId = sessionId;
            this.traceId = traceId;
            this.emitter = emitter;
            this.audit = new AgentRunAudit(traceId);
        }
    }
}


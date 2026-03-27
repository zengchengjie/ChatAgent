package com.chatagent.agent;

import com.chatagent.agent.audit.AgentRunAudit;
import com.chatagent.agent.dto.AgentChatResponse;
import com.chatagent.agent.dto.AgentStepResponse;
import com.chatagent.chat.ChatService;
import com.chatagent.chat.MessageRole;
import com.chatagent.chat.dto.MessageResponse;
import com.chatagent.common.ApiException;
import com.chatagent.config.AgentProperties;
import com.chatagent.llm.AssistantTurn;
import com.chatagent.llm.DashScopeClient;
import com.chatagent.llm.ToolCall;
import com.chatagent.tools.ToolRegistry;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import java.io.IOException;
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
import io.micrometer.core.instrument.MeterRegistry;
import org.slf4j.MDC;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

/**
 * Agent 编排层：把「会话历史 + 工具」组装成 OpenAI 兼容请求，循环调用大模型并在本地执行工具，直到得到最终文本或超出步数。
 *
 * <p>
 * 数据流要点：用户一句 → 落库 USER → 每轮从 DB 重载为 {@code messages[]} →
 * {@link DashScopeClient} → 若有 tool_calls 则落库
 * ASSISTANT(含 tool_calls_json) 与各 TOOL(含 tool_call_id) → 再请求模型；若无 tool_calls
 * 则落库最终 ASSISTANT 并结束。
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class AgentService {

    /** 注入到每条请求最前面的 system 消息，引导模型在合适时调用工具并把工具结果用自然语言总结给用户。 */
    private static final String SYSTEM_PROMPT = "You are a helpful assistant. For complex questions, first provide a concise 1-3 step plan, "
            + "then execute the plan by calling tools step-by-step when needed, and finally give a conclusion "
            + "with supporting evidence sources (tool outputs or relevant context). "
            + "Use provided tools when they help answer accurately.";

    private static final Pattern PLAN_LINE_PATTERN = Pattern.compile("^(?:\\d+[.)]|[-*])\\s+(.*)$");

    private final ChatService chatService;
    private final DashScopeClient dashScopeClient;
    private final ToolRegistry toolRegistry;
    private final AgentProperties agentProperties;
    private final ObjectMapper objectMapper;
    private final MeterRegistry meterRegistry;
    private final ExecutorService toolPool = Executors.newCachedThreadPool();
    private long lastToolCallTime = 0;
    private final Object toolLock = new Object();

    /**
     * 非流式对话接口：同步执行 Agent 逻辑并返回完整结果。
     * 
     * <p>
     * 执行流程：
     * <ol>
     *   <li>用户输入持久化</li>
     *   <li>循环调用 LLM（最多 maxSteps 轮）</li>
     *   <li>如有 tool_calls：执行工具（受 maxToolCallsPerTurn 和 maxToolCallsTotal 限制）</li>
     *   <li>如无 tool_calls：返回最终回复</li>
     *   <li>超出步数：抛出异常</li>
     * </ol>
     * 
     * <p>
     * 执行护栏：
     * <ul>
     *   <li>maxSteps：推理步数上限（默认 8）</li>
     *   <li>maxToolCallsPerTurn：每轮工具调用上限（默认 2）</li>
     *   <li>maxToolCallsTotal：全局工具调用上限（默认 6）</li>
     *   <li>toolTimeoutMs：单工具超时（默认 2000ms）</li>
     *   <li>maxPlanSteps：计划步骤上限（默认 3）</li>
     * </ul>
     * 
     * @param userId 用户 ID
     * @param sessionId 会话 ID
     * @param userContent 用户输入内容
     * @return 包含回复文本和工具步骤的响应对象
     * @throws ApiException 当触发执行护栏（如 maxSteps、maxToolCallsTotal）时抛出
     */
    public AgentChatResponse chatSync(Long userId, String sessionId, String userContent) {
        String traceId = UUID.randomUUID().toString();
        MDC.put("traceId", traceId);
        AgentRunAudit audit = new AgentRunAudit(traceId);
        try {
            // 1) 用户输入先持久化，保证后续 reload 能拼进上下文
            chatService.appendMessage(userId, sessionId, MessageRole.USER, userContent, null, null);
            List<AgentStepResponse> steps = new ArrayList<>();
            boolean planRecorded = false;
            // 2) 每轮：以 DB 为单一事实来源重载 messages，避免内存与库不一致
            for (int step = 0; step < agentProperties.getMaxSteps(); step++) {
                audit.onRound();
                ArrayNode messages = reloadMessages(userId, sessionId);
                ArrayNode tools = toolRegistry.toolsJson();
                long t0 = System.currentTimeMillis();
                // 3) 工具轮使用非流式 completion，便于解析 tool_calls
                AssistantTurn turn = dashScopeClient.chatCompletion(messages, tools);
                log.info(
                        "event=llm_call traceId={} ms={} finishReason={} toolCalls={}",
                        traceId,
                        System.currentTimeMillis() - t0,
                        turn.getFinishReason(),
                        turn.getToolCalls().size());
                if (!planRecorded) {
                    planRecorded = recordPlanStepsSafe(steps, turn.getContent());
                }
                if (!turn.getToolCalls().isEmpty()) {
                    int perTurnLimit = agentProperties.getMaxToolCallsPerTurn();
                    int totalLimit = agentProperties.getMaxToolCallsTotal();
                    int actualPerTurn = turn.getToolCalls().size();
                    if (actualPerTurn > perTurnLimit) {
                        log.warn(
                                "event=guardrail_hit traceId={} reason=maxToolCallsPerTurn limit={} actual={}",
                                traceId,
                                perTurnLimit,
                                actualPerTurn);
                    }
                    // 4a) 模型要求调用工具：先记下 assistant（含 tool_calls），再执行工具并写入 tool 消息
                    String asstContent = turn.getContent() != null && !turn.getContent().isBlank()
                            ? turn.getContent()
                            : null;
                    chatService.appendMessage(
                            userId,
                            sessionId,
                            MessageRole.ASSISTANT,
                            asstContent,
                            turn.getRawToolCallsJson(),
                            null);
                    List<ToolCall> toRun = actualPerTurn > perTurnLimit
                            ? turn.getToolCalls().subList(0, Math.max(0, perTurnLimit))
                            : turn.getToolCalls();
                    for (ToolCall tc : toRun) {
                        if (audit.toolCalls() >= totalLimit) {
                            log.warn(
                                    "event=guardrail_hit traceId={} reason=maxToolCallsTotal limit={} actual={}",
                                    traceId,
                                    totalLimit,
                                    audit.toolCalls());
                            throw new ApiException(HttpStatus.BAD_REQUEST, "Guardrail hit: max tool calls reached");
                        }
                        int perToolLimit = agentProperties.getMaxToolCallsPerTool();
                        if (audit.toolCallsFor(tc.getName()) >= perToolLimit) {
                            log.warn(
                                    "event=guardrail_hit traceId={} reason=maxToolCallsPerTool limit={} actual={} tool={}",
                                    traceId,
                                    perToolLimit,
                                    audit.toolCallsFor(tc.getName()) + 1,
                                    tc.getName());
                            meterRegistry.counter("chatagent.guardrail.hit", "reason", "maxToolCallsPerTool", "engine", "self").increment();
                            continue;
                        }
                        enforceToolInterval(traceId);
                        long t1 = System.currentTimeMillis();
                        String result;
                        try {
                            result = executeToolWithRetry(tc.getName(), tc.getArgumentsJson(), traceId, 1);
                            log.info(
                                    "event=tool_call traceId={} tool={} ms={} ok=true",
                                    traceId,
                                    tc.getName(),
                                    System.currentTimeMillis() - t1);
                        } catch (TimeoutException te) {
                            log.warn(
                                    "event=guardrail_hit traceId={} reason=toolTimeoutMs limit={} actual={}",
                                    traceId,
                                    agentProperties.getToolTimeoutMs(),
                                    agentProperties.getToolTimeoutMs());
                            result = "Error: tool timed out";
                            log.warn(
                                    "event=tool_call traceId={} tool={} ms={} ok=false err={}",
                                    traceId,
                                    tc.getName(),
                                    System.currentTimeMillis() - t1,
                                    te.toString());
                        } catch (Exception e) {
                            result = "Error: " + e.getMessage();
                            log.warn(
                                    "event=tool_call traceId={} tool={} ms={} ok=false err={}",
                                    traceId,
                                    tc.getName(),
                                    System.currentTimeMillis() - t1,
                                    e.toString());
                        }
                        audit.onToolCall(tc.getName());
                        meterRegistry.counter("chatagent.tool.calls", "tool", tc.getName(), "engine", "self").increment();
                        steps.add(
                                AgentStepResponse.builder()
                                        .type("tool")
                                        .stepIndex(null)
                                        .toolName(tc.getName())
                                        .detail(truncate(result, 500))
                                        .build());
                        chatService.appendMessage(
                                userId, sessionId, MessageRole.TOOL, result, null, tc.getId());
                    }
                    continue;
                }
                // 4b) 无 tool_calls：本轮为最终自然语言回复，落库后返回
                String text = turn.getContent() != null ? turn.getContent() : "";
                chatService.appendMessage(userId, sessionId, MessageRole.ASSISTANT, text, null, null);
                return AgentChatResponse.builder().reply(text).steps(steps).build();
            }
            audit.setMaxStepsHit(true);
            throw new ApiException(
                    HttpStatus.BAD_REQUEST,
                    "Agent stopped: maximum reasoning steps (" + agentProperties.getMaxSteps() + ") reached.");
        } finally {
            log.info(
                    "event=agent_summary traceId={} rounds={} toolCalls={} ragCalls={} maxStepsHit={}",
                    traceId,
                    audit.rounds(),
                    audit.toolCalls(),
                    audit.ragCalls(),
                    audit.maxStepsHit());
            meterRegistry.counter("chatagent.agent.summary", "engine", "self").increment();
            MDC.remove("traceId");
        }
    }

    /**
     * SSE 流式对话接口：使用 Server-Sent Events 实时流式返回 Agent 执行过程。
     * 
     * <p>
     * 执行流程：
     * <ol>
     *   <li>用户输入持久化</li>
     *   <li>循环调用 LLM（最多 maxSteps 轮）</li>
     *   <li>工具阶段：推送 tool_start / tool_end 事件</li>
     *   <li>计划阶段：推送 plan_start / plan_step / plan_done 事件</li>
     *   <li>最终回复：使用 DashScope 流式 API 实现 token 级流式</li>
     *   <li>结束：推送 done 事件</li>
     * </ol>
     * 
     * <p>
     * SSE 事件类型：
     * <ul>
     *   <li>plan_start：计划开始，包含步骤数量</li>
     *   <li>plan_step：单个计划步骤</li>
     *   <li>plan_done：计划完成</li>
     *   <li>tool_start：工具调用开始</li>
     *   <li>tool_end：工具调用结束</li>
     *   <li>delta：LLM 响应片段（token 级）</li>
     *   <li>guardrail：执行护栏触发</li>
     *   <li>error：错误信息</li>
     *   <li>done：对话完成</li>
     * </ul>
     * 
     * <p>
     * 执行护栏（同 chatSync）：
     * <ul>
     *   <li>maxSteps：推理步数上限</li>
     *   <li>maxToolCallsPerTurn：每轮工具调用上限</li>
     *   <li>maxToolCallsTotal：全局工具调用上限</li>
     *   <li>toolTimeoutMs：单工具超时</li>
     *   <li>maxPlanSteps：计划步骤上限</li>
     * </ul>
     * 
     * @param userId 用户 ID
     * @param sessionId 会话 ID
     * @param userContent 用户输入内容
     * @param emitter SSE 发送器
     */
    public void chatStream(Long userId, String sessionId, String userContent, SseEmitter emitter) {
        String traceId = UUID.randomUUID().toString();
        MDC.put("traceId", traceId);
        AgentRunAudit audit = new AgentRunAudit(traceId);
        try {
            chatService.appendMessage(userId, sessionId, MessageRole.USER, userContent, null, null);
            boolean planSent = false;
            for (int step = 0; step < agentProperties.getMaxSteps(); step++) {
                audit.onRound();
                ArrayNode messages = reloadMessages(userId, sessionId);
                ArrayNode tools = toolRegistry.toolsJson();
                long t0 = System.currentTimeMillis();
                AssistantTurn turn = dashScopeClient.chatCompletion(messages, tools);
                log.info(
                        "event=llm_call traceId={} ms={} finishReason={} toolCalls={}",
                        traceId,
                        System.currentTimeMillis() - t0,
                        turn.getFinishReason(),
                        turn.getToolCalls().size());
                if (!planSent) {
                    planSent = emitPlanEventsSafe(emitter, turn.getContent(), traceId);
                }
                if (!turn.getToolCalls().isEmpty()) {
                    int perTurnLimit = agentProperties.getMaxToolCallsPerTurn();
                    int totalLimit = agentProperties.getMaxToolCallsTotal();
                    int actualPerTurn = turn.getToolCalls().size();
                    if (actualPerTurn > perTurnLimit) {
                        log.warn(
                                "event=guardrail_hit traceId={} reason=maxToolCallsPerTurn limit={} actual={}",
                                traceId,
                                perTurnLimit,
                                actualPerTurn);
                        sendJson(
                                emitter,
                                "guardrail",
                                Map.of("reason", "maxToolCallsPerTurn", "limit", perTurnLimit, "actual",
                                        actualPerTurn));
                    }
                    // 工具轮：落库 + 向客户端推送 tool_start / tool_end，便于 UI 展示过程
                    String asstContent = turn.getContent() != null && !turn.getContent().isBlank()
                            ? turn.getContent()
                            : null;
                    chatService.appendMessage(
                            userId,
                            sessionId,
                            MessageRole.ASSISTANT,
                            asstContent,
                            turn.getRawToolCallsJson(),
                            null);
                    List<ToolCall> toRun = actualPerTurn > perTurnLimit
                            ? turn.getToolCalls().subList(0, Math.max(0, perTurnLimit))
                            : turn.getToolCalls();
                    for (ToolCall tc : toRun) {
                        if (audit.toolCalls() >= totalLimit) {
                            log.warn(
                                    "event=guardrail_hit traceId={} reason=maxToolCallsTotal limit={} actual={}",
                                    traceId,
                                    totalLimit,
                                    audit.toolCalls());
                            sendJson(
                                    emitter,
                                    "guardrail",
                                    Map.of("reason", "maxToolCallsTotal", "limit", totalLimit, "actual",
                                            audit.toolCalls()));
                            sendJson(emitter, "error", Map.of("message", "Guardrail hit: max tool calls reached"));
                            return;
                        }
                        int perToolLimit = agentProperties.getMaxToolCallsPerTool();
                        if (audit.toolCallsFor(tc.getName()) >= perToolLimit) {
                            log.warn(
                                    "event=guardrail_hit traceId={} reason=maxToolCallsPerTool limit={} actual={} tool={}",
                                    traceId,
                                    perToolLimit,
                                    audit.toolCallsFor(tc.getName()) + 1,
                                    tc.getName());
                            meterRegistry.counter("chatagent.guardrail.hit", "reason", "maxToolCallsPerTool", "engine", "self").increment();
                            sendJson(
                                    emitter,
                                    "guardrail",
                                    Map.of("reason", "maxToolCallsPerTool", "limit", perToolLimit, "actual", audit.toolCallsFor(tc.getName()) + 1, "tool", tc.getName()));
                            continue;
                        }
                        enforceToolInterval(traceId);
                        sendJson(
                                emitter,
                                "tool_start",
                                Map.of("name", tc.getName(), "id", tc.getId() != null ? tc.getId() : ""));
                        long t1 = System.currentTimeMillis();
                        String result;
                        try {
                            result = executeToolWithRetry(tc.getName(), tc.getArgumentsJson(), traceId, 1);
                            log.info(
                                    "event=tool_call traceId={} tool={} ms={} ok=true",
                                    traceId,
                                    tc.getName(),
                                    System.currentTimeMillis() - t1);
                        } catch (TimeoutException te) {
                            log.warn(
                                    "event=guardrail_hit traceId={} reason=toolTimeoutMs limit={} actual={}",
                                    traceId,
                                    agentProperties.getToolTimeoutMs(),
                                    agentProperties.getToolTimeoutMs());
                            sendJson(
                                    emitter,
                                    "guardrail",
                                    Map.of(
                                            "reason",
                                            "toolTimeoutMs",
                                            "limit",
                                            agentProperties.getToolTimeoutMs(),
                                            "actual",
                                            agentProperties.getToolTimeoutMs(),
                                            "tool",
                                            tc.getName()));
                            result = "Error: tool timed out";
                            log.warn(
                                    "event=tool_call traceId={} tool={} ms={} ok=false err={}",
                                    traceId,
                                    tc.getName(),
                                    System.currentTimeMillis() - t1,
                                    te.toString());
                        } catch (Exception e) {
                            result = "Error: " + e.getMessage();
                            log.warn(
                                    "event=tool_call traceId={} tool={} ms={} ok=false err={}",
                                    traceId,
                                    tc.getName(),
                                    System.currentTimeMillis() - t1,
                                    e.toString());
                        }
                        audit.onToolCall(tc.getName());
                        meterRegistry.counter("chatagent.tool.calls", "tool", tc.getName(), "engine", "self").increment();
                        sendJson(
                                emitter,
                                "tool_end",
                                Map.of(
                                        "name",
                                        tc.getName(),
                                        "ok",
                                        true,
                                        "detail",
                                        truncate(result, 500)));
                        chatService.appendMessage(
                                userId, sessionId, MessageRole.TOOL, result, null, tc.getId());
                    }
                    continue;
                }
                // 最终回复：使用 DashScope 流式 API 实现真实 token 级流式
                final StringBuilder fullText = new StringBuilder();
                dashScopeClient.streamCompletion(
                        messages,
                        tools,
                        delta -> {
                            fullText.append(delta);
                            sendJson(emitter, "delta", Map.of("text", delta));
                        });
                chatService.appendMessage(userId, sessionId, MessageRole.ASSISTANT, fullText.toString(), null, null);
                sendJson(emitter, "done", Map.of("ok", true));
                return;
            }
            audit.setMaxStepsHit(true);
            sendJson(
                    emitter,
                    "error",
                    Map.of(
                            "message",
                            "Agent stopped: maximum reasoning steps ("
                                    + agentProperties.getMaxSteps()
                                    + ") reached."));
        } catch (ApiException e) {
            sendJson(emitter, "error", Map.of("message", e.getMessage()));
        } catch (Exception e) {
            log.error("event=agent_stream_failed traceId={}", traceId, e);
            sendJson(emitter, "error", Map.of("message", "Internal error"));
        } finally {
            log.info(
                    "event=agent_summary traceId={} rounds={} toolCalls={} ragCalls={} maxStepsHit={}",
                    traceId,
                    audit.rounds(),
                    audit.toolCalls(),
                    audit.ragCalls(),
                    audit.maxStepsHit());
            meterRegistry.counter("chatagent.agent.summary", "engine", "self").increment();
            MDC.remove("traceId");
            emitter.complete();
        }
    }

    /**
     * 从数据库加载当前会话全部消息，并转换为 DashScope/OpenAI 兼容的 messages 数组。
     * 
     * <p>
     * 用途：确保每轮对话都以数据库为单一事实来源，避免内存与数据库不一致。
     * 
     * @param userId 用户 ID
     * @param sessionId 会话 ID
     * @return DashScope/OpenAI 兼容的 messages 数组
     */
    private ArrayNode reloadMessages(Long userId, String sessionId) {
        List<MessageResponse> rows = chatService.listMessages(userId, sessionId);
        return toLlmMessages(rows);
    }

    /**
     * 将数据库消息行转换为 DashScope/OpenAI 兼容的 messages 数组。
     * 
     * <p>
     * 转换规则：
     * <ul>
     *   <li>USER → role: "user"</li>
     *   <li>ASSISTANT → role: "assistant"（含 tool_calls_json 转为 tool_calls）</li>
     *   <li>TOOL → role: "tool"（含 tool_call_id）</li>
     *   <li>SYSTEM → 忽略</li>
     * </ul>
     * 
     * @param rows 数据库消息行列表
     * @return DashScope/OpenAI 兼容的 messages 数组
     */
    private ArrayNode toLlmMessages(List<MessageResponse> rows) {
        ArrayNode arr = objectMapper.createArrayNode();
        arr.add(
                objectMapper
                        .createObjectNode()
                        .put("role", "system")
                        .put("content", SYSTEM_PROMPT));
        for (MessageResponse r : rows) {
            switch (r.getRole()) {
                case USER -> arr.add(
                        objectMapper
                                .createObjectNode()
                                .put("role", "user")
                                .put("content", r.getContent() != null ? r.getContent() : ""));
                case ASSISTANT -> {
                    var o = objectMapper.createObjectNode();
                    o.put("role", "assistant");
                    if (r.getContent() != null) {
                        o.put("content", r.getContent());
                    } else {
                        o.putNull("content");
                    }
                    if (r.getToolCallsJson() != null && !r.getToolCallsJson().isBlank()) {
                        try {
                            o.set("tool_calls", objectMapper.readTree(r.getToolCallsJson()));
                        } catch (Exception ignored) {
                            // skip malformed
                        }
                    }
                    arr.add(o);
                }
                case TOOL -> {
                    var o = objectMapper.createObjectNode();
                    o.put("role", "tool");
                    o.put("content", r.getContent() != null ? r.getContent() : "");
                    if (r.getToolCallId() != null) {
                        o.put("tool_call_id", r.getToolCallId());
                    }
                    arr.add(o);
                }
                case SYSTEM -> {
                    /* ignore persisted system */ }
            }
        }
        return arr;
    }

    /**
     * 截断字符串到指定长度，超出部分用 "..." 表示。
     * 
     * @param s 原始字符串
     * @param max 最大长度
     * @return 截断后的字符串
     */
    private static String truncate(String s, int max) {
        if (s == null) {
            return "";
        }
        return s.length() <= max ? s : s.substring(0, max) + "...";
    }

    /**
     * 向 SSE 发送 JSON 格式事件。
     * 
     * @param emitter SSE 发送器
     * @param event 事件名称
     * @param data 事件数据
     */
    private static void sendJson(SseEmitter emitter, String event, Map<String, ?> data) {
        try {
            emitter.send(SseEmitter.event().name(event).data(data));
        } catch (IOException e) {
            throw new RuntimeException(e);
        }
    }

    /**
     * 带重试机制的工具执行方法。
     * 
     * <p>
     * 执行流程：
     * <ol>
     *   <li>尝试执行工具（最多 maxRetries + 1 次）</li>
     *   <li>失败时等待 100ms 后重试</li>
     *   <li>全部失败后抛出异常</li>
     * </ol>
     * 
     * @param toolName 工具名称
     * @param argsJson 工具参数（JSON 格式）
     * @param traceId 追踪 ID
     * @param attempt 尝试次数（保留参数，暂未使用）
     * @return 工具执行结果
     * @throws Exception 工具执行异常
     */
    private String executeToolWithRetry(String toolName, String argsJson, String traceId, int attempt)
            throws Exception {
        int maxRetries = agentProperties.getMaxToolRetries();
        for (int retry = 0; retry <= maxRetries; retry++) {
            try {
                return executeToolWithTimeout(toolName, argsJson, traceId);
            } catch (Exception e) {
                log.warn(
                        "event=tool_retry traceId={} tool={} attempt={} max={} err={}",
                        traceId,
                        toolName,
                        retry + 1,
                        maxRetries + 1,
                        e.getMessage());
                if (retry < maxRetries) {
                    Thread.sleep(100);
                } else {
                    throw e;
                }
            }
        }
        throw new RuntimeException("Tool execution failed after all retries");
    }

    /**
     * 超时控制的工具执行方法。
     * 
     * <p>
     * 使用线程池异步执行工具，并在超时后中断执行。
     * 
     * @param toolName 工具名称
     * @param argsJson 工具参数（JSON 格式）
     * @param traceId 追踪 ID
     * @return 工具执行结果
     * @throws TimeoutException 工具执行超时
     * @throws Exception 其他执行异常
     */
    private String executeToolWithTimeout(String toolName, String argsJson, String traceId)
            throws Exception {
        long timeoutMs = agentProperties.getToolTimeoutMs();
        Future<String> f = toolPool.submit(
                (Callable<String>) () -> toolRegistry.execute(toolName, argsJson, traceId));
        try {
            return f.get(timeoutMs, TimeUnit.MILLISECONDS);
        } catch (TimeoutException te) {
            f.cancel(true);
            throw te;
        } catch (ExecutionException ee) {
            Throwable c = ee.getCause();
            if (c instanceof Exception e) {
                throw e;
            }
            throw new RuntimeException(c);
        }
    }

    /**
     * 强制工具调用间隔限制。
     * 
     * <p>
     * 确保两次工具调用之间至少间隔 minToolIntervalMs 毫秒，防止调用过于频繁。
     * 
     * @param traceId 追踪 ID
     */
    private void enforceToolInterval(String traceId) {
        long minInterval = agentProperties.getMinToolIntervalMs();
        if (minInterval <= 0) {
            return;
        }
        synchronized (toolLock) {
            long now = System.currentTimeMillis();
            long elapsed = now - lastToolCallTime;
            if (elapsed < minInterval) {
                long waitTime = minInterval - elapsed;
                log.debug(
                        "event=tool_interval_wait traceId={} elapsed={} wait={}ms",
                        traceId,
                        elapsed,
                        waitTime);
                try {
                    Thread.sleep(waitTime);
                } catch (InterruptedException ie) {
                    Thread.currentThread().interrupt();
                }
            }
            lastToolCallTime = System.currentTimeMillis();
        }
    }

    /**
     * 安全地记录计划步骤（非 SSE 模式）。
     * 
     * <p>
     * 执行流程：
     * <ol>
     *   <li>提取计划步骤</li>
     *   <li>检查步骤数量是否超过 maxPlanSteps（超过则降级）</li>
     *   <li>添加到步骤列表</li>
     * </ol>
     * 
     * @param steps 步骤列表
     * @param assistantContent LLM 返回的计划文本
     * @return 是否成功记录计划
     */
    private boolean recordPlanStepsSafe(List<AgentStepResponse> steps, String assistantContent) {
        try {
            List<String> planSteps = extractPlanSteps(assistantContent);
            if (planSteps.isEmpty()) {
                return false;
            }
            int maxPlanSteps = agentProperties.getMaxPlanSteps();
            if (planSteps.size() > maxPlanSteps) {
                log.warn("event=guardrail_hit reason=maxPlanSteps limit={} actual={}", maxPlanSteps, planSteps.size());
                return false;
            }
            for (int i = 0; i < planSteps.size(); i++) {
                steps.add(
                        AgentStepResponse.builder()
                                .type("plan")
                                .stepIndex(i + 1)
                                .detail(planSteps.get(i))
                                .build());
            }
            return true;
        } catch (Exception e) {
            // 规划可视化失败时降级，不中断主链路
            log.warn("event=plan_record_failed err={}", e.toString());
            return false;
        }
    }

    /**
     * 安全地发送计划事件（SSE 模式）。
     * 
     * <p>
     * 执行流程：
     * <ol>
     *   <li>提取计划步骤</li>
     *   <li>检查步骤数量是否超过 maxPlanSteps（超过则降级并发送 guardrail 事件）</li>
     *   <li>推送 plan_start / plan_step / plan_done 事件</li>
     * </ol>
     * 
     * @param emitter SSE 发送器
     * @param assistantContent LLM 返回的计划文本
     * @param traceId 追踪 ID
     * @return 是否成功发送计划事件
     */
    private boolean emitPlanEventsSafe(SseEmitter emitter, String assistantContent, String traceId) {
        try {
            List<String> planSteps = extractPlanSteps(assistantContent);
            if (planSteps.isEmpty()) {
                return false;
            }
            int maxPlanSteps = agentProperties.getMaxPlanSteps();
            if (planSteps.size() > maxPlanSteps) {
                log.warn(
                        "event=guardrail_hit traceId={} reason=maxPlanSteps limit={} actual={}",
                        traceId,
                        maxPlanSteps,
                        planSteps.size());
                sendJson(
                        emitter,
                        "guardrail",
                        Map.of("reason", "maxPlanSteps", "limit", maxPlanSteps, "actual", planSteps.size()));
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
            // 规划可视化失败时降级，不中断主链路
            log.warn("event=plan_emit_failed traceId={} err={}", traceId, e.toString());
            return false;
        }
    }

    /**
     * 从 LLM 返回的计划文本中提取计划步骤。
     * 
     * <p>
     * 提取规则：
     * <ul>
     *   <li>匹配数字序号（如 "1. "、"2)"）</li>
     *   <li>匹配项目符号（如 "- "、"* "）</li>
     *   <li>最多提取 3 个步骤</li>
     * </ul>
     * 
     * @param assistantContent LLM 返回的计划文本
     * @return 提取的计划步骤列表
     */
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
}

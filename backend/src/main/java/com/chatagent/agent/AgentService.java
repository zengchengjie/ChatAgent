package com.chatagent.agent;

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
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.slf4j.MDC;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

/**
 * Agent 编排层：把「会话历史 + 工具」组装成 OpenAI 兼容请求，循环调用大模型并在本地执行工具，直到得到最终文本或超出步数。
 *
 * <p>数据流要点：用户一句 → 落库 USER → 每轮从 DB 重载为 {@code messages[]} → {@link DashScopeClient} → 若有 tool_calls 则落库
 * ASSISTANT(含 tool_calls_json) 与各 TOOL(含 tool_call_id) → 再请求模型；若无 tool_calls 则落库最终 ASSISTANT 并结束。
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class AgentService {

    /** 注入到每条请求最前面的 system 消息，引导模型在合适时调用工具并把工具结果用自然语言总结给用户。 */
    private static final String SYSTEM_PROMPT =
            "You are a helpful assistant. Use the provided tools when they help answer accurately. "
                    + "After using a tool, summarize the result for the user in natural language.";

    private final ChatService chatService;
    private final DashScopeClient dashScopeClient;
    private final ToolRegistry toolRegistry;
    private final AgentProperties agentProperties;
    private final ObjectMapper objectMapper;

    /** 非流式：一次 HTTP 返回完整 reply + 工具步骤摘要（用于 JSON API）。 */
    public AgentChatResponse chatSync(Long userId, String sessionId, String userContent) {
        String traceId = UUID.randomUUID().toString();
        MDC.put("traceId", traceId);
        try {
            // 1) 用户输入先持久化，保证后续 reload 能拼进上下文
            chatService.appendMessage(userId, sessionId, MessageRole.USER, userContent, null, null);
            List<AgentStepResponse> steps = new ArrayList<>();
            // 2) 每轮：以 DB 为单一事实来源重载 messages，避免内存与库不一致
            for (int step = 0; step < agentProperties.getMaxSteps(); step++) {
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
                if (!turn.getToolCalls().isEmpty()) {
                    // 4a) 模型要求调用工具：先记下 assistant（含 tool_calls），再执行工具并写入 tool 消息
                    String asstContent =
                            turn.getContent() != null && !turn.getContent().isBlank()
                                    ? turn.getContent()
                                    : null;
                    chatService.appendMessage(
                            userId,
                            sessionId,
                            MessageRole.ASSISTANT,
                            asstContent,
                            turn.getRawToolCallsJson(),
                            null);
                    for (ToolCall tc : turn.getToolCalls()) {
                        long t1 = System.currentTimeMillis();
                        String result;
                        try {
                            result = toolRegistry.execute(tc.getName(), tc.getArgumentsJson(), traceId);
                            log.info(
                                    "event=tool_call traceId={} tool={} ms={} ok=true",
                                    traceId,
                                    tc.getName(),
                                    System.currentTimeMillis() - t1);
                        } catch (Exception e) {
                            result = "Error: " + e.getMessage();
                            log.warn(
                                    "event=tool_call traceId={} tool={} ms={} ok=false err={}",
                                    traceId,
                                    tc.getName(),
                                    System.currentTimeMillis() - t1,
                                    e.toString());
                        }
                        steps.add(
                                AgentStepResponse.builder()
                                        .type("tool")
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
            throw new ApiException(
                    HttpStatus.BAD_REQUEST,
                    "Agent stopped: maximum reasoning steps (" + agentProperties.getMaxSteps() + ") reached.");
        } finally {
            MDC.remove("traceId");
        }
    }

    /**
     * SSE：工具阶段逻辑与 {@link #chatSync} 相同；最终助手文本使用 DashScope 流式 API 实现真实 token 级流式。
     */
    public void chatStream(Long userId, String sessionId, String userContent, SseEmitter emitter) {
        String traceId = UUID.randomUUID().toString();
        MDC.put("traceId", traceId);
        try {
            chatService.appendMessage(userId, sessionId, MessageRole.USER, userContent, null, null);
            for (int step = 0; step < agentProperties.getMaxSteps(); step++) {
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
                if (!turn.getToolCalls().isEmpty()) {
                    // 工具轮：落库 + 向客户端推送 tool_start / tool_end，便于 UI 展示过程
                    String asstContent =
                            turn.getContent() != null && !turn.getContent().isBlank()
                                    ? turn.getContent()
                                    : null;
                    chatService.appendMessage(
                            userId,
                            sessionId,
                            MessageRole.ASSISTANT,
                            asstContent,
                            turn.getRawToolCallsJson(),
                            null);
                    for (ToolCall tc : turn.getToolCalls()) {
                        sendJson(
                                emitter,
                                "tool_start",
                                Map.of("name", tc.getName(), "id", tc.getId() != null ? tc.getId() : ""));
                        long t1 = System.currentTimeMillis();
                        String result;
                        try {
                            result = toolRegistry.execute(tc.getName(), tc.getArgumentsJson(), traceId);
                            log.info(
                                    "event=tool_call traceId={} tool={} ms={} ok=true",
                                    traceId,
                                    tc.getName(),
                                    System.currentTimeMillis() - t1);
                        } catch (Exception e) {
                            result = "Error: " + e.getMessage();
                            log.warn(
                                    "event=tool_call traceId={} tool={} ms={} ok=false err={}",
                                    traceId,
                                    tc.getName(),
                                    System.currentTimeMillis() - t1,
                                    e.toString());
                        }
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
            MDC.remove("traceId");
            emitter.complete();
        }
    }

    /** 从 DB 拉取当前会话全部消息，并转换为 DashScope/OpenAI 兼容的 messages 数组。 */
    private ArrayNode reloadMessages(Long userId, String sessionId) {
        List<MessageResponse> rows = chatService.listMessages(userId, sessionId);
        return toLlmMessages(rows);
    }

    /**
     * DB 行 → API 消息：assistant 行的 tool_calls_json 对应模型返回的 tool_calls；tool 行必须带 tool_call_id 与 assistant 里 id 对齐。
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
                case SYSTEM -> { /* ignore persisted system */ }
            }
        }
        return arr;
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
}

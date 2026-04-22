package com.chatagent.it.graph;

import com.chatagent.it.SemanticCacheService;
import io.opentelemetry.api.trace.Span;
import io.opentelemetry.api.trace.Tracer;
import io.opentelemetry.context.Scope;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.bsc.langgraph4j.CompiledGraph;
import org.bsc.langgraph4j.RunnableConfig;
import org.bsc.langgraph4j.state.StateSnapshot;
import org.springframework.stereotype.Service;

@Service
@RequiredArgsConstructor
@Slf4j
public class ITSupportGraphService {

    private final CompiledGraph<ITSupportGraphState> graph;
    private final Tracer tracer;
    private final com.chatagent.it.SemanticCacheService semanticCache;
    private final com.chatagent.it.ITSupportTools tools;

    /** 暂存 pending 上下文（sessionId → context） */
    private final ConcurrentHashMap<String, PendingContext> pendingContexts = new ConcurrentHashMap<>();

    /** 对话入口 */
    public GraphResult chat(String sessionId, String userId, String message) {
        Span span = tracer.spanBuilder("graph.chat").startSpan();
        try (Scope scope = span.makeCurrent()) {
            span.setAttribute("session.id", sessionId);
            span.setAttribute("user.id", userId);
            span.setAttribute("user.message.length", message.length());

            RunnableConfig config = RunnableConfig.builder()
                    .threadId(sessionId)
                    .build();

            Map<String, Object> inputState = getOrCreateState(sessionId, userId);

            // 添加用户消息到 messages channel（字符串格式，便于序列化）
            @SuppressWarnings("unchecked")
            List<String> messages = (List<String>) inputState.computeIfAbsent(
                    ITSupportGraphConfig.CHANNEL_MESSAGES, k -> new ArrayList<String>());
            messages.add("USER:" + message);

            // 语义缓存查询
            SemanticCacheService.CacheHit cacheHit = semanticCache.findBestMatch(message);
            if (cacheHit.hit()) {
                span.setAttribute("cache.hit", true);
                span.setAttribute("cache.similarity", cacheHit.similarity());
                pendingContexts.remove(sessionId);
                return GraphResult.ok(cacheHit.answer());
            }
            span.setAttribute("cache.hit", false);

            // 调用图执行
            Optional<ITSupportGraphState> resultOpt = graph.invoke(inputState, config);

            if (resultOpt.isEmpty()) {
                return GraphResult.error("Graph execution returned no result");
            }

            ITSupportGraphState state = resultOpt.get();
            String pendingAction = (String) state.value(ITSupportGraphConfig.CHANNEL_PENDING_ACTION).orElse(null);
            Boolean needsApproval = (Boolean) state.value(ITSupportGraphConfig.CHANNEL_NEEDS_APPROVAL).orElse(false);

            if (needsApproval && pendingAction != null && !pendingAction.isBlank()) {
                // 中断：等待用户审批
                pendingContexts.put(sessionId, new PendingContext(pendingAction, new ConcurrentHashMap<>(state.data())));
                span.setAttribute("result.pending", true);
                return GraphResult.pending(pendingAction);
            }

            String response = (String) state.value(ITSupportGraphConfig.CHANNEL_LLM_RESPONSE).orElse(null);
            String finalResponse = response != null ? response : "（无回复）";
            // 写入语义缓存
            semanticCache.put(message, finalResponse, cacheHit.queryVector());
            pendingContexts.remove(sessionId);
            span.setAttribute("result.pending", false);
            return GraphResult.ok(finalResponse);

        } catch (Exception e) {
            span.recordException(e);
            log.error("Graph execution error sessionId={}", sessionId, e);
            return GraphResult.error("执行出错: " + e.getMessage());
        } finally {
            span.end();
        }
    }

    /** 用户审批 */
    public GraphResult approve(String sessionId, boolean approved) {
        Span span = tracer.spanBuilder("graph.approve").startSpan();
        try (Scope scope = span.makeCurrent()) {
            span.setAttribute("session.id", sessionId);
            span.setAttribute("approved", approved);

            PendingContext ctx = pendingContexts.get(sessionId);
            if (ctx == null) {
                return GraphResult.error("没有待审批的操作");
            }

            // approved=true：直接执行工具并返回结果，跳过图的路由逻辑
            // approved=false：返回拒绝消息
            if (approved) {
                Map<String, Object> snapshot = ctx.snapshotData();
                String toolName = (String) snapshot.get(ITSupportGraphConfig.CHANNEL_TOOL_NAME);
                String toolInput = (String) snapshot.getOrDefault(ITSupportGraphConfig.CHANNEL_TOOL_INPUT, "");
                String userId = (String) snapshot.getOrDefault(ITSupportGraphConfig.CHANNEL_USER_ID, "unknown");

                // 直接调用工具
                String toolResult = executeToolDirect(toolName, toolInput, userId);
                span.setAttribute("tool.result", toolResult);
                pendingContexts.remove(sessionId);
                return GraphResult.ok(toolResult);
            } else {
                pendingContexts.remove(sessionId);
                return GraphResult.ok("已取消操作。");
            }

        } catch (Exception e) {
            span.recordException(e);
            log.error("Graph approval error sessionId={}", sessionId, e);
            return GraphResult.error("审批失败: " + e.getMessage());
        } finally {
            span.end();
        }
    }

    /** 直接执行工具（绕过图） */
    private String executeToolDirect(String toolName, String toolInput, String userId) {
        if (toolName == null || toolName.isBlank()) {
            return "（无工具可执行）";
        }
        try {
            if ("generateTicket".equals(toolName)) {
                return tools.generateTicket(userId, toolInput);
            }
            // 其他工具暂不支持直接调用
            return "未知工具: " + toolName;
        } catch (Exception e) {
            return "工具执行失败: " + e.getMessage();
        }
    }

    private Map<String, Object> getOrCreateState(String sessionId, String userId) {
        RunnableConfig config = RunnableConfig.builder().threadId(sessionId).build();
        try {
            StateSnapshot<ITSupportGraphState> snapshot = graph.getState(config);
            if (snapshot != null && snapshot.state() != null) {
                return new ConcurrentHashMap<>(snapshot.state().data());
            }
        } catch (Exception e) {
            log.debug("No checkpoint found for sessionId={}", sessionId, e);
        }
        Map<String, Object> initial = ITSupportGraphState.initial(sessionId, userId);
        initial.entrySet().removeIf(e -> e.getValue() == null);
        return new ConcurrentHashMap<>(initial);
    }

    public record PendingContext(String pendingAction, Map<String, Object> snapshotData) {}

    public record GraphResult(String response, String pendingAction, String error, boolean pending) {
        public static GraphResult ok(String response) {
            return new GraphResult(response, null, null, false);
        }
        public static GraphResult pending(String pendingAction) {
            return new GraphResult(null, pendingAction, null, true);
        }
        public static GraphResult error(String error) {
            return new GraphResult(null, null, error, false);
        }
    }
}

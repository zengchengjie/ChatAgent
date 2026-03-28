package com.chatagent.agent;

import com.chatagent.agent.dto.AgentChatRequest;
import com.chatagent.agent.dto.AgentChatResponse;
import com.chatagent.agent.engine.AgentEngineRouter;
import com.chatagent.chat.ChatService;
import com.chatagent.common.CancellationToken;
import com.chatagent.common.IdempotencyService;
import com.chatagent.security.JwtPrincipal;
import com.chatagent.security.SecurityUtils;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.validation.Valid;
import java.time.Duration;
import java.util.concurrent.Executor;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

/**
 * Agent 控制器：提供 Agent 对话的 HTTP 接口。
 * 
 * <p>
 * 功能：
 * <ul>
 *   <li>同步对话：/api/agent/chat - 同步返回完整结果</li>
 *   <li>流式对话：/api/agent/chat/stream - SSE 流式返回</li>
 * </ul>
 * 
 * <p>
 * 安全控制：
 * <ul>
 *   <li>JWT 认证：所有接口需要有效的 JWT Token</li>
 *   <li>会话归属校验：确保用户只能访问自己的会话</li>
 *   <li>参数校验：使用 @Valid 校验请求参数</li>
 * </ul>
 * 
 * <p>
 * 使用示例：
 * <pre>{@code
 * // 同步对话
 * POST /api/agent/chat
 * {
 *   "sessionId": "xxx",
 *   "content": "你好"
 * }
 * 
 * // 流式对话
 * POST /api/agent/chat/stream
 * {
 *   "sessionId": "xxx",
 *   "content": "你好"
 * }
 * }</pre>
 */
@RestController
@RequestMapping("/api/agent")
public class AgentController {

    private final AgentEngineRouter agentEngineRouter;
    private final ChatService chatService;
    private final Executor agentTaskExecutor;
    private final IdempotencyService idempotencyService;
    private final ObjectMapper objectMapper;

    /**
     * 构造控制器。
     * 
     * @param agentService Agent 服务
     * @param chatService 会话服务
     * @param agentTaskExecutor Agent 任务线程池（用于流式接口）
     */
    public AgentController(
            AgentEngineRouter agentEngineRouter,
            ChatService chatService,
            @Qualifier("agentTaskExecutor") Executor agentTaskExecutor,
            IdempotencyService idempotencyService,
            ObjectMapper objectMapper) {
        this.agentEngineRouter = agentEngineRouter;
        this.chatService = chatService;
        this.agentTaskExecutor = agentTaskExecutor;
        this.idempotencyService = idempotencyService;
        this.objectMapper = objectMapper;
    }

    /**
     * 同步对话接口。
     * 
     * <p>
     * 执行流程：
     * <ol>
     *   <li>校验 JWT 认证</li>
     *   <li>校验会话归属</li>
     *   <li>调用 AgentService.chatSync() 执行 Agent 逻辑</li>
     *   <li>返回完整结果</li>
     * </ol>
     * 
     * @param req 对话请求
     * @return 对话响应
     */
    @PostMapping("/chat")
    public AgentChatResponse chat(
            @Valid @RequestBody AgentChatRequest req,
            @RequestHeader(value = "Idempotency-Key", required = false) String idempotencyKey) {
        JwtPrincipal p = SecurityUtils.requirePrincipal();
        chatService.requireSessionOwned(p.userId(), req.getSessionId());
        if (idempotencyKey == null || idempotencyKey.isBlank()) {
            return agentEngineRouter.chatSync(p.userId(), req.getSessionId(), req.getContent());
        }
        String key = "idem:agent:chat:" + p.userId() + ":" + req.getSessionId() + ":" + idempotencyKey.trim();
        var r = idempotencyService.tryAcquire(key, Duration.ofMinutes(10));
        if (!r.acquired()) {
            if (r.value() != null && !r.value().isBlank() && !"__processing__".equals(r.value())) {
                try {
                    return objectMapper.readValue(r.value(), AgentChatResponse.class);
                } catch (Exception ignored) {
                    // fall through
                }
            }
            throw new com.chatagent.common.ApiException(org.springframework.http.HttpStatus.CONFLICT, "Duplicate request in progress");
        }
        AgentChatResponse resp = agentEngineRouter.chatSync(p.userId(), req.getSessionId(), req.getContent());
        try {
            idempotencyService.storeResult(key, objectMapper.writeValueAsString(resp), Duration.ofMinutes(10));
        } catch (Exception ignored) {
            // best effort
        }
        return resp;
    }

    /**
     * 流式对话接口（SSE）。
     * 
     * <p>
     * 执行流程：
     * <ol>
     *   <li>校验 JWT 认证</li>
     *   <li>校验会话归属</li>
     *   <li>创建 SseEmitter（300 秒超时）</li>
     *   <li>异步执行 AgentService.chatStream()，通过 emitter 推送事件</li>
     *   <li>立即返回 emitter，保持连接</li>
     * </ol>
     * 
     * <p>
     * 事件类型：
     * <ul>
     *   <li>delta：模型输出的文本片段</li>
     *   <li>tool_start：工具调用开始</li>
     *   <li>tool_end：工具调用结束</li>
     *   <li>error：错误信息</li>
     *   <li>done：对话完成</li>
     * </ul>
     * 
     * @param req 对话请求
     * @return SseEmitter（300 秒超时）
     */
    @PostMapping("/chat/stream")
    public SseEmitter chatStream(
            @Valid @RequestBody AgentChatRequest req,
            @RequestHeader(value = "Idempotency-Key", required = false) String idempotencyKey) {
        JwtPrincipal p = SecurityUtils.requirePrincipal();
        chatService.requireSessionOwned(p.userId(), req.getSessionId());
        SseEmitter emitter = new SseEmitter(300_000L);
        CancellationToken cancelToken = new CancellationToken();
        emitter.onCompletion(cancelToken::cancel);
        emitter.onTimeout(cancelToken::cancel);
        emitter.onError((e) -> cancelToken.cancel());

        if (idempotencyKey != null && !idempotencyKey.isBlank()) {
            String key = "idem:agent:stream:" + p.userId() + ":" + req.getSessionId() + ":" + idempotencyKey.trim();
            var r = idempotencyService.tryAcquire(key, Duration.ofMinutes(10));
            if (!r.acquired()) {
                // For streaming we don't replay; just fail fast.
                agentTaskExecutor.execute(
                        () -> {
                            try {
                                emitter.send(SseEmitter.event().name("error").data("{\"message\":\"Duplicate request\"}"));
                            } catch (Exception ignored) {
                            } finally {
                                emitter.complete();
                            }
                        });
                return emitter;
            }
        }
        // 立即返回 emitter，具体推送在后台线程完成（长时间持有连接）
        agentTaskExecutor.execute(
                () -> agentEngineRouter.chatStream(p.userId(), req.getSessionId(), req.getContent(), emitter, cancelToken));
        return emitter;
    }
}

package com.chatagent.agent;

import com.chatagent.agent.dto.AgentChatRequest;
import com.chatagent.agent.dto.AgentChatResponse;
import com.chatagent.chat.ChatService;
import com.chatagent.security.JwtPrincipal;
import com.chatagent.security.SecurityUtils;
import jakarta.validation.Valid;
import java.util.concurrent.Executor;
import org.springframework.beans.factory.annotation.Qualifier;
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

    private final AgentService agentService;
    private final ChatService chatService;
    private final Executor agentTaskExecutor;

    /**
     * 构造控制器。
     * 
     * @param agentService Agent 服务
     * @param chatService 会话服务
     * @param agentTaskExecutor Agent 任务线程池（用于流式接口）
     */
    public AgentController(
            AgentService agentService,
            ChatService chatService,
            @Qualifier("agentTaskExecutor") Executor agentTaskExecutor) {
        this.agentService = agentService;
        this.chatService = chatService;
        this.agentTaskExecutor = agentTaskExecutor;
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
    public AgentChatResponse chat(@Valid @RequestBody AgentChatRequest req) {
        JwtPrincipal p = SecurityUtils.requirePrincipal();
        chatService.requireSessionOwned(p.userId(), req.getSessionId());
        return agentService.chatSync(p.userId(), req.getSessionId(), req.getContent());
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
    public SseEmitter chatStream(@Valid @RequestBody AgentChatRequest req) {
        JwtPrincipal p = SecurityUtils.requirePrincipal();
        chatService.requireSessionOwned(p.userId(), req.getSessionId());
        SseEmitter emitter = new SseEmitter(300_000L);
        // 立即返回 emitter，具体推送在后台线程完成（长时间持有连接）
        agentTaskExecutor.execute(
                () -> agentService.chatStream(p.userId(), req.getSessionId(), req.getContent(), emitter));
        return emitter;
    }
}

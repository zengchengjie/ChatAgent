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

/** Agent HTTP 入口：校验会话归属后调用编排服务；流式接口通过线程池异步写 SSE，避免阻塞 Web 容器线程。 */
@RestController
@RequestMapping("/api/agent")
public class AgentController {

    private final AgentService agentService;
    private final ChatService chatService;
    private final Executor agentTaskExecutor;

    public AgentController(
            AgentService agentService,
            ChatService chatService,
            @Qualifier("agentTaskExecutor") Executor agentTaskExecutor) {
        this.agentService = agentService;
        this.chatService = chatService;
        this.agentTaskExecutor = agentTaskExecutor;
    }

    @PostMapping("/chat")
    public AgentChatResponse chat(@Valid @RequestBody AgentChatRequest req) {
        JwtPrincipal p = SecurityUtils.requirePrincipal();
        chatService.requireSessionOwned(p.userId(), req.getSessionId());
        return agentService.chatSync(p.userId(), req.getSessionId(), req.getContent());
    }

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

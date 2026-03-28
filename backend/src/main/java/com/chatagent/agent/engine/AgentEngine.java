package com.chatagent.agent.engine;

import com.chatagent.agent.dto.AgentChatResponse;
import com.chatagent.common.CancellationToken;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

public interface AgentEngine {
    String name();

    AgentChatResponse chatSync(Long userId, String sessionId, String userContent);

    void chatStream(Long userId, String sessionId, String userContent, SseEmitter emitter, CancellationToken cancelToken);
}


package com.chatagent.agent.engine;

import com.chatagent.agent.AgentService;
import com.chatagent.agent.dto.AgentChatResponse;
import com.chatagent.config.AppProperties;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

@Service
@RequiredArgsConstructor
public class AgentEngineRouter {

    private final AppProperties appProperties;
    private final AgentService selfEngine;
    private final Langchain4jAgentEngine langchain4jEngine;

    public AgentChatResponse chatSync(Long userId, String sessionId, String userContent) {
        return pick().chatSync(userId, sessionId, userContent);
    }

    public void chatStream(Long userId, String sessionId, String userContent, SseEmitter emitter) {
        pick().chatStream(userId, sessionId, userContent, emitter);
    }

    private AgentEngine pick() {
        String engine = appProperties.getAgent().getEngine();
        if ("self".equalsIgnoreCase(engine)) {
            return new AgentEngine() {
                @Override
                public String name() {
                    return "self";
                }

                @Override
                public AgentChatResponse chatSync(Long userId, String sessionId, String userContent) {
                    return selfEngine.chatSync(userId, sessionId, userContent);
                }

                @Override
                public void chatStream(Long userId, String sessionId, String userContent, SseEmitter emitter) {
                    selfEngine.chatStream(userId, sessionId, userContent, emitter);
                }
            };
        }
        return langchain4jEngine;
    }
}


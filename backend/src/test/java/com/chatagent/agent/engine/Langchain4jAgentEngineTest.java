package com.chatagent.agent.engine;

import static org.junit.jupiter.api.Assertions.*;

import com.chatagent.agent.dto.AgentChatRequest;
import com.chatagent.agent.dto.AgentChatResponse;
import com.chatagent.chat.ChatService;
import com.chatagent.config.AgentProperties;
import com.chatagent.config.DashScopeProperties;
import com.chatagent.tools.ToolRegistry;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.micrometer.core.instrument.MeterRegistry;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

@ExtendWith(MockitoExtension.class)
class Langchain4jAgentEngineTest {

    @Mock private ChatService chatService;

    @Mock private DashScopeProperties dashScopeProperties;

    @Mock private AgentProperties agentProperties;

    @Mock private ToolRegistry toolRegistry;

    @Mock private ObjectMapper objectMapper;

    @Mock private MeterRegistry meterRegistry;

    @Test
    void testEngineName() {
        Langchain4jAgentEngine engine =
                new Langchain4jAgentEngine(
                        chatService,
                        dashScopeProperties,
                        agentProperties,
                        toolRegistry,
                        objectMapper,
                        meterRegistry);
        assertEquals("langchain4j", engine.name());
    }
}
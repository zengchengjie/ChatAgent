package com.chatagent.it.graph;

import dev.langchain4j.data.message.ChatMessage;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.bsc.langgraph4j.state.AgentState;

public class ITSupportGraphState extends AgentState {

    public ITSupportGraphState(Map<String, Object> data) {
        super(data);
    }

    @SuppressWarnings("unchecked")
    public List<ChatMessage> messages() {
        return (List<ChatMessage>) data().get(ITSupportGraphConfig.CHANNEL_MESSAGES);
    }

    public Optional<Object> pendingAction() {
        return value(ITSupportGraphConfig.CHANNEL_PENDING_ACTION);
    }

    public Optional<Object> approved() {
        return value(ITSupportGraphConfig.CHANNEL_APPROVED);
    }

    public Optional<Object> routerOutput() {
        return value(ITSupportGraphConfig.CHANNEL_ROUTER_OUTPUT);
    }

    public Optional<Object> toolName() {
        return value(ITSupportGraphConfig.CHANNEL_TOOL_NAME);
    }

    public Optional<Object> toolInput() {
        return value(ITSupportGraphConfig.CHANNEL_TOOL_INPUT);
    }

    public Optional<Object> llmResponse() {
        return value(ITSupportGraphConfig.CHANNEL_LLM_RESPONSE);
    }

    public Optional<Object> userId() {
        return value(ITSupportGraphConfig.CHANNEL_USER_ID);
    }

    public Optional<Object> sessionId() {
        return value(ITSupportGraphConfig.CHANNEL_SESSION_ID);
    }

    public static Map<String, Object> initial(String sessionId, String userId) {
        return Map.of(
                ITSupportGraphConfig.CHANNEL_SESSION_ID,
                sessionId,
                ITSupportGraphConfig.CHANNEL_USER_ID,
                userId,
                ITSupportGraphConfig.CHANNEL_MESSAGES,
                new java.util.ArrayList<ChatMessage>(),
                ITSupportGraphConfig.CHANNEL_APPROVED,
                null,
                ITSupportGraphConfig.CHANNEL_PENDING_ACTION,
                null,
                ITSupportGraphConfig.CHANNEL_LLM_RESPONSE,
                null,
                ITSupportGraphConfig.CHANNEL_TOOL_NAME,
                null,
                ITSupportGraphConfig.CHANNEL_TOOL_INPUT,
                null,
                ITSupportGraphConfig.CHANNEL_ROUTER_OUTPUT,
                null);
    }
}

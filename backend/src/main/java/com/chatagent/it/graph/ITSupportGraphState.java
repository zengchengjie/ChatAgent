package com.chatagent.it.graph;

import dev.langchain4j.data.message.AiMessage;
import dev.langchain4j.data.message.ChatMessage;
import dev.langchain4j.data.message.UserMessage;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.bsc.langgraph4j.state.AgentState;

public class ITSupportGraphState extends AgentState {

    public ITSupportGraphState(Map<String, Object> data) {
        super(data);
    }

    @SuppressWarnings("unchecked")
    public List<String> messages() {
        return (List<String>) data().get(ITSupportGraphConfig.CHANNEL_MESSAGES);
    }

    /** 从消息列表提取最后一个用户消息文本 */
    public String lastUserMessage() {
        List<String> msgs = messages();
        if (msgs == null || msgs.isEmpty()) return "";
        for (int i = msgs.size() - 1; i >= 0; i--) {
            String m = msgs.get(i);
            if (m != null && m.startsWith("USER:")) {
                return m.substring(5);
            }
        }
        return "";
    }

    /** 从消息列表构建 LLM 调用的 ChatMessage 列表 */
    @SuppressWarnings("unchecked")
    public List<ChatMessage> toChatMessages() {
        List<String> msgs = messages();
        if (msgs == null || msgs.isEmpty()) return List.of();
        List<ChatMessage> result = new java.util.ArrayList<>();
        for (String m : msgs) {
            if (m == null) continue;
            if (m.startsWith("USER:")) {
                result.add(UserMessage.from(m.substring(5)));
            } else if (m.startsWith("AI:")) {
                result.add(dev.langchain4j.data.message.AiMessage.from(m.substring(3)));
            }
        }
        return result;
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
        Map<String, Object> map = new java.util.HashMap<>();
        map.put(ITSupportGraphConfig.CHANNEL_SESSION_ID, sessionId != null ? sessionId : "default");
        map.put(ITSupportGraphConfig.CHANNEL_USER_ID, userId != null ? userId : "unknown");
        map.put(ITSupportGraphConfig.CHANNEL_MESSAGES, new java.util.ArrayList<String>());
        map.put(ITSupportGraphConfig.CHANNEL_APPROVED, false);
        map.put(ITSupportGraphConfig.CHANNEL_PENDING_ACTION, "");
        map.put(ITSupportGraphConfig.CHANNEL_LLM_RESPONSE, "");
        map.put(ITSupportGraphConfig.CHANNEL_TOOL_NAME, "");
        map.put(ITSupportGraphConfig.CHANNEL_TOOL_INPUT, "");
        map.put(ITSupportGraphConfig.CHANNEL_ROUTER_OUTPUT, "");
        map.put(ITSupportGraphConfig.CHANNEL_NEEDS_APPROVAL, false);
        return map;
    }
}

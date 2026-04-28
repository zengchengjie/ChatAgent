package com.chatagent.chat;

import com.chatagent.chat.dto.MessageResponse;
import com.chatagent.chat.dto.SessionResponse;
import com.chatagent.common.ApiException;
import com.chatagent.config.DashScopeProperties;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** 会话与消息的持久化与权限边界；Agent 侧通过 {@link #appendMessage} 写入各角色行，供下一轮拼 LLM 上下文。 */
@Service
@RequiredArgsConstructor
public class ChatService {

    private final ChatSessionRepository sessionRepository;
    private final ChatMessageRepository messageRepository;
    private final DashScopeProperties dashScopeProperties;

    @Transactional
    public SessionResponse createSession(Long userId, String title, String model) {
        ChatSession s = new ChatSession();
        s.setId(UUID.randomUUID().toString());
        s.setUserId(userId);
        s.setTitle(title != null && !title.isBlank() ? title : "New chat");
        s.setModel(model != null && !model.isBlank() ? model : dashScopeProperties.getModel());
        Instant now = Instant.now();
        s.setCreatedAt(now);
        s.setUpdatedAt(now);
        sessionRepository.save(s);
        return toSessionResponse(s);
    }

    @Transactional(readOnly = true)
    public List<SessionResponse> listSessions(Long userId) {
        return sessionRepository.findByUserIdOrderByUpdatedAtDesc(userId).stream()
                .map(this::toSessionResponse)
                .toList();
    }

    @Transactional(readOnly = true)
    public List<MessageResponse> listMessages(Long userId, String sessionId) {
        ChatSession s = getOwnedSession(userId, sessionId);
        return messageRepository.findBySessionIdOrderByCreatedAtAsc(s.getId()).stream()
                .map(this::toMessageResponse)
                .toList();
    }

    @Transactional
    public void deleteSession(Long userId, String sessionId) {
        ChatSession s = getOwnedSession(userId, sessionId);
        messageRepository.deleteBySessionId(s.getId());
        sessionRepository.delete(s);
    }

    @Transactional
    public SessionResponse updateSessionTitle(Long userId, String sessionId, String title) {
        ChatSession s = getOwnedSession(userId, sessionId);
        if (title != null && !title.isBlank()) {
            s.setTitle(title);
        }
        s.setUpdatedAt(Instant.now());
        sessionRepository.save(s);
        return toSessionResponse(s);
    }

    /**
     * @param toolCallsJson 仅 ASSISTANT 且含 tool_calls 时：存模型返回的 JSON 数组字符串，供回放 OpenAI 多轮格式
     * @param toolCallId 仅 TOOL 消息：必须与上一轮 assistant.tool_calls[].id 一致
     */
    @Transactional
    public ChatMessage appendMessage(
            Long userId,
            String sessionId,
            MessageRole role,
            String content,
            String toolCallsJson,
            String toolCallId) {
        getOwnedSession(userId, sessionId);
        ChatMessage m = new ChatMessage();
        m.setSessionId(sessionId);
        m.setRole(role);
        m.setContent(content);
        m.setToolCallsJson(toolCallsJson);
        m.setToolCallId(toolCallId);
        m.setCreatedAt(Instant.now());
        messageRepository.save(m);
        touchSession(sessionId);
        return m;
    }

    @Transactional(readOnly = true)
    public ChatSession requireSessionOwned(Long userId, String sessionId) {
        return getOwnedSession(userId, sessionId);
    }

    @Transactional(readOnly = true)
    public Optional<String> findSessionModel(Long userId, String sessionId) {
        ChatSession s = getOwnedSession(userId, sessionId);
        String model = s.getModel();
        if (model == null || model.isBlank()) {
            return Optional.empty();
        }
        return Optional.of(model);
    }

    private ChatSession getOwnedSession(Long userId, String sessionId) {
        ChatSession s =
                sessionRepository
                        .findById(sessionId)
                        .orElseThrow(() -> new ApiException(HttpStatus.NOT_FOUND, "Session not found"));
        if (!s.getUserId().equals(userId)) {
            throw new ApiException(HttpStatus.FORBIDDEN, "Forbidden");
        }
        return s;
    }

    private void touchSession(String sessionId) {
        sessionRepository
                .findById(sessionId)
                .ifPresent(
                        s -> {
                            s.setUpdatedAt(Instant.now());
                            sessionRepository.save(s);
                        });
    }

    private SessionResponse toSessionResponse(ChatSession s) {
        return SessionResponse.builder()
                .id(s.getId())
                .title(s.getTitle())
                .model(s.getModel())
                .createdAt(s.getCreatedAt())
                .updatedAt(s.getUpdatedAt())
                .build();
    }

    private MessageResponse toMessageResponse(ChatMessage m) {
        return MessageResponse.builder()
                .id(m.getId())
                .role(m.getRole())
                .content(m.getContent())
                .toolCallsJson(m.getToolCallsJson())
                .toolCallId(m.getToolCallId())
                .createdAt(m.getCreatedAt())
                .build();
    }
}

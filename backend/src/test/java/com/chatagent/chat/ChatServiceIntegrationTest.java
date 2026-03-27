package com.chatagent.chat;

import static org.junit.jupiter.api.Assertions.*;

import com.chatagent.chat.dto.CreateSessionRequest;
import com.chatagent.chat.dto.MessageResponse;
import com.chatagent.chat.dto.SessionResponse;
import com.chatagent.security.JwtPrincipal;
import com.chatagent.security.SecurityUtils;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.transaction.annotation.Transactional;

@SpringBootTest
@ActiveProfiles("test")
@Import(com.chatagent.TestRedisConfig.class)
@Transactional
class ChatServiceIntegrationTest {

    @Autowired private ChatService chatService;

    private static final Long TEST_USER_ID = 1L;

    @Test
    void testCreateSession() {
        SessionResponse session = chatService.createSession(TEST_USER_ID, "Test Session");
        assertNotNull(session);
        assertNotNull(session.getId());
        assertEquals("Test Session", session.getTitle());
        assertNotNull(session.getCreatedAt());
        assertNotNull(session.getUpdatedAt());
    }

    @Test
    void testCreateSessionWithDefaultTitle() {
        SessionResponse session = chatService.createSession(TEST_USER_ID, null);
        assertNotNull(session);
        assertEquals("New chat", session.getTitle());
    }

    @Test
    void testListSessions() {
        chatService.createSession(TEST_USER_ID, "Session 1");
        chatService.createSession(TEST_USER_ID, "Session 2");

        List<SessionResponse> sessions = chatService.listSessions(TEST_USER_ID);
        assertTrue(sessions.size() >= 2);
    }

    @Test
    void testAppendMessage() {
        SessionResponse session = chatService.createSession(TEST_USER_ID, "Test Session");

        ChatMessage userMessage =
                chatService.appendMessage(
                        TEST_USER_ID, session.getId(), MessageRole.USER, "Hello", null, null);
        assertNotNull(userMessage);
        assertEquals(MessageRole.USER, userMessage.getRole());
        assertEquals("Hello", userMessage.getContent());

        ChatMessage assistantMessage =
                chatService.appendMessage(
                        TEST_USER_ID, session.getId(), MessageRole.ASSISTANT, "Hi there!", null, null);
        assertNotNull(assistantMessage);
        assertEquals(MessageRole.ASSISTANT, assistantMessage.getRole());
        assertEquals("Hi there!", assistantMessage.getContent());
    }

    @Test
    void testListMessages() {
        SessionResponse session = chatService.createSession(TEST_USER_ID, "Test Session");

        chatService.appendMessage(TEST_USER_ID, session.getId(), MessageRole.USER, "Hello", null, null);
        chatService.appendMessage(
                TEST_USER_ID, session.getId(), MessageRole.ASSISTANT, "Hi there!", null, null);

        List<MessageResponse> messages = chatService.listMessages(TEST_USER_ID, session.getId());
        assertEquals(2, messages.size());
        assertEquals(MessageRole.USER, messages.get(0).getRole());
        assertEquals(MessageRole.ASSISTANT, messages.get(1).getRole());
    }

    @Test
    void testDeleteSession() {
        SessionResponse session = chatService.createSession(TEST_USER_ID, "Test Session");
        String sessionId = session.getId();

        chatService.deleteSession(TEST_USER_ID, sessionId);

        List<SessionResponse> sessions = chatService.listSessions(TEST_USER_ID);
        assertFalse(sessions.stream().anyMatch(s -> s.getId().equals(sessionId)));
    }

    @Test
    void testUpdateSessionTitle() {
        SessionResponse session = chatService.createSession(TEST_USER_ID, "Original Title");
        String sessionId = session.getId();

        SessionResponse updated =
                chatService.updateSessionTitle(TEST_USER_ID, sessionId, "Updated Title");
        assertNotNull(updated);
        assertEquals("Updated Title", updated.getTitle());
    }

    @Test
    void testAccessOtherUserSession() {
        SessionResponse session = chatService.createSession(1L, "User 1 Session");
        String sessionId = session.getId();

        assertThrows(
                com.chatagent.common.ApiException.class,
                () -> chatService.listMessages(2L, sessionId));
    }
}
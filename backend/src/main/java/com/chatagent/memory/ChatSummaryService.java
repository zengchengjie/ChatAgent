package com.chatagent.memory;

import com.chatagent.chat.ChatService;
import com.chatagent.chat.MessageRole;
import com.chatagent.chat.dto.MessageResponse;
import com.chatagent.common.ApiException;
import com.chatagent.llm.DashScopeClient;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import java.time.Instant;
import java.util.List;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
@RequiredArgsConstructor
public class ChatSummaryService {

    private final ChatService chatService;
    private final ChatSummaryRepository chatSummaryRepository;
    private final DashScopeClient dashScopeClient;
    private final ObjectMapper objectMapper;

    // conservative defaults; can be extracted to properties later
    private static final int TRIGGER_MESSAGE_COUNT = 30;
    private static final int KEEP_LAST_MESSAGES = 12;

    @Transactional
    public void maybeSummarize(Long userId, String sessionId, String modelOverride) {
        List<MessageResponse> rows = chatService.listMessages(userId, sessionId);
        if (rows.size() <= TRIGGER_MESSAGE_COUNT) {
            return;
        }

        ChatSummary existing = chatSummaryRepository.findById(sessionId).orElse(null);
        long lastSummarizedId = existing != null && existing.getLastMessageId() != null ? existing.getLastMessageId() : 0L;

        int cutoffIdx = rows.size() - KEEP_LAST_MESSAGES;
        if (cutoffIdx <= 0) {
            return;
        }
        Long cutoffIdObj = rows.get(cutoffIdx - 1).getId();
        if (cutoffIdObj == null) {
            return;
        }
        long cutoffMessageId = cutoffIdObj;
        if (cutoffMessageId <= lastSummarizedId) {
            return;
        }

        StringBuilder newPart = new StringBuilder();
        for (MessageResponse r : rows) {
            if (r.getId() == null || r.getId() <= lastSummarizedId || r.getId() > cutoffMessageId) {
                continue;
            }
            if (r.getRole() == MessageRole.SYSTEM) {
                continue;
            }
            String c = r.getContent() == null ? "" : r.getContent();
            if (c.isBlank()) {
                continue;
            }
            newPart.append(r.getRole().name()).append(": ").append(c).append("\n");
        }
        if (newPart.isEmpty()) {
            return;
        }

        String prior = existing != null ? existing.getSummary() : "";
        String prompt =
                "You maintain a running conversation summary for an AI assistant.\n"
                        + "Update the summary using ONLY the new messages.\n\n"
                        + "Requirements:\n"
                        + "- Keep key user goals, constraints, preferences, decisions, and open TODOs.\n"
                        + "- Keep it concise (<= 1200 Chinese characters or <= 2000 ASCII chars).\n"
                        + "- Do not include tool outputs verbatim; summarize them.\n\n"
                        + "Previous summary:\n"
                        + (prior == null || prior.isBlank() ? "(none)" : prior)
                        + "\n\nNew messages:\n"
                        + newPart;

        ArrayNode messages = objectMapper.createArrayNode();
        messages.add(objectMapper.createObjectNode().put("role", "system").put("content", "You are a careful summarizer."));
        messages.add(objectMapper.createObjectNode().put("role", "user").put("content", prompt));

        String summaryText;
        try {
            summaryText =
                    modelOverride != null && !modelOverride.isBlank()
                            ? dashScopeClient.chatCompletion(messages, null, modelOverride).getContent()
                            : dashScopeClient.chatCompletion(messages, null).getContent();
        } catch (ApiException e) {
            // Fail open: summarization should never break chat
            return;
        } catch (Exception e) {
            return;
        }
        if (summaryText == null || summaryText.isBlank()) {
            return;
        }

        ChatSummary s = existing != null ? existing : new ChatSummary();
        s.setSessionId(sessionId);
        s.setSummary(summaryText.trim());
        s.setLastMessageId(cutoffMessageId);
        s.setUpdatedAt(Instant.now());
        chatSummaryRepository.save(s);
    }
}


package com.chatagent.it;

import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequiredArgsConstructor
public class ITSupportChatController {

    private final ITSupportChatService chatService;

    @PostMapping("/api/chat")
    public ChatResponse chat(
            @Valid @RequestBody ChatRequest request,
            @RequestHeader(value = "X-Session-Id", required = false) String sessionId) {
        String sid = (sessionId == null || sessionId.isBlank()) ? "default-session" : sessionId.trim();
        String answer = chatService.chat(sid, request.message());
        return new ChatResponse(answer);
    }
}

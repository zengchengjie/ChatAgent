package com.chatagent.chat;

import com.chatagent.chat.dto.CreateSessionRequest;
import com.chatagent.chat.dto.MessageResponse;
import com.chatagent.chat.dto.SessionResponse;
import com.chatagent.security.JwtPrincipal;
import com.chatagent.security.SecurityUtils;
import jakarta.validation.Valid;
import java.util.List;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/sessions")
@RequiredArgsConstructor
public class SessionController {

    private final ChatService chatService;

    @PostMapping
    public SessionResponse create(@Valid @RequestBody(required = false) CreateSessionRequest req) {
        JwtPrincipal p = SecurityUtils.requirePrincipal();
        String title = req != null ? req.getTitle() : null;
        return chatService.createSession(p.userId(), title);
    }

    @GetMapping
    public List<SessionResponse> list() {
        JwtPrincipal p = SecurityUtils.requirePrincipal();
        return chatService.listSessions(p.userId());
    }

    @GetMapping("/{sessionId}/messages")
    public List<MessageResponse> messages(@PathVariable String sessionId) {
        JwtPrincipal p = SecurityUtils.requirePrincipal();
        return chatService.listMessages(p.userId(), sessionId);
    }
}

package com.chatagent.it.graph;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/graph")
@RequiredArgsConstructor
public class ITSupportGraphController {

    private final ITSupportGraphService graphService;

    public record ChatRequest(
            @NotBlank String message,
            @NotBlank String userId
    ) {}

    public record ApproveRequest(
            @NotBlank String sessionId,
            boolean approved
    ) {}

    public record ChatResponse(
            String response,
            String pendingAction,
            String error,
            boolean pending
    ) {}

    @PostMapping("/chat")
    public ChatResponse chat(
            @RequestHeader(value = "X-Session-Id", required = false) String sessionId,
            @Valid @RequestBody ChatRequest request) {
        String sid = (sessionId == null || sessionId.isBlank()) ? java.util.UUID.randomUUID().toString() : sessionId.trim();
        ITSupportGraphService.GraphResult result = graphService.chat(sid, request.userId(), request.message());
        return new ChatResponse(result.response(), result.pendingAction(), result.error(), result.pending());
    }

    @PostMapping("/approve")
    public ChatResponse approve(@Valid @RequestBody ApproveRequest request) {
        ITSupportGraphService.GraphResult result = graphService.approve(request.sessionId(), request.approved);
        return new ChatResponse(result.response(), result.pendingAction(), result.error(), result.pending());
    }
}

package com.chatagent.chat.dto;

import com.chatagent.chat.MessageRole;
import java.time.Instant;
import lombok.Builder;
import lombok.Value;

@Value
@Builder
public class MessageResponse {
    Long id;
    MessageRole role;
    String content;
    String toolCallsJson;
    String toolCallId;
    Instant createdAt;
}

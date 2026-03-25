package com.chatagent.chat.dto;

import java.time.Instant;
import lombok.Builder;
import lombok.Value;

@Value
@Builder
public class SessionResponse {
    String id;
    String title;
    Instant createdAt;
    Instant updatedAt;
}

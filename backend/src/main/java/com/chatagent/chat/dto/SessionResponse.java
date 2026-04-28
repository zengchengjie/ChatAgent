package com.chatagent.chat.dto;

import java.time.Instant;
import lombok.Builder;
import lombok.Value;

@Value
@Builder
public class SessionResponse {
    String id;
    String title;
    String model;
    Instant createdAt;
    Instant updatedAt;
}

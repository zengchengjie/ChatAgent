package com.chatagent.agent.dto;

import jakarta.validation.constraints.NotBlank;
import lombok.Data;

@Data
public class AgentChatRequest {

    @NotBlank private String sessionId;
    @NotBlank private String content;
}

package com.chatagent.agent.dto;

import java.util.List;
import lombok.Builder;
import lombok.Value;

@Value
@Builder
public class AgentChatResponse {
    String reply;
    List<AgentStepResponse> steps;
}

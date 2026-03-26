package com.chatagent.agent.dto;

import lombok.Builder;
import lombok.Value;

@Value
@Builder
public class AgentStepResponse {
    String type;
    Integer stepIndex;
    String toolName;
    String detail;
}

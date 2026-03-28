package com.chatagent.llm;

import java.util.List;
import lombok.Builder;
import lombok.Value;

@Value
@Builder
public class AssistantTurn {
    String content;
    List<ToolCall> toolCalls;
    String finishReason;
    String rawToolCallsJson;
    TokenUsage usage;
}

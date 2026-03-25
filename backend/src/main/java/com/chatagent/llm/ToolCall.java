package com.chatagent.llm;

import lombok.Builder;
import lombok.Value;

@Value
@Builder
public class ToolCall {
    String id;
    String name;
    String argumentsJson;
}

package com.chatagent.llm;

import lombok.Builder;
import lombok.Value;

@Value
@Builder
public class TokenUsage {
    Integer promptTokens;
    Integer completionTokens;
    Integer totalTokens;
    boolean estimated;
}


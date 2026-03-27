package com.chatagent.agent.audit;

import java.util.HashMap;
import java.util.Map;

/**
 * 标准化运行审计模型：用于 self/langchain4j 两种引擎统一记录与汇总。
 */
public class AgentRunAudit {

    private final String traceId;
    private int rounds;
    private int toolCalls;
    private int ragCalls;
    private boolean maxStepsHit;
    private final Map<String, Integer> toolCallsByName = new HashMap<>();

    public AgentRunAudit(String traceId) {
        this.traceId = traceId;
    }

    public void onRound() {
        rounds++;
    }

    public void onToolCall(String toolName) {
        toolCalls++;
        if ("search_knowledge".equals(toolName)) {
            ragCalls++;
        }
        toolCallsByName.merge(toolName, 1, Integer::sum);
    }

    public int toolCallsFor(String toolName) {
        return toolCallsByName.getOrDefault(toolName, 0);
    }

    public void setMaxStepsHit(boolean maxStepsHit) {
        this.maxStepsHit = maxStepsHit;
    }

    public String traceId() {
        return traceId;
    }

    public int rounds() {
        return rounds;
    }

    public int toolCalls() {
        return toolCalls;
    }

    public int ragCalls() {
        return ragCalls;
    }

    public boolean maxStepsHit() {
        return maxStepsHit;
    }
}


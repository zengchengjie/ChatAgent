package com.chatagent.config;

import lombok.Getter;
import lombok.Setter;
import org.springframework.boot.context.properties.ConfigurationProperties;

@Getter
@Setter
@ConfigurationProperties(prefix = "agent")
public class AgentProperties {

    private int maxSteps = 8;
    /** 每轮模型返回的 tool_calls 最多执行多少个（护栏）。 */
    private int maxToolCallsPerTurn = 2;
    /** 一次请求全局最多执行多少个 tool_call（护栏）。 */
    private int maxToolCallsTotal = 6;
    /** 单个工具执行超时（毫秒）。 */
    private long toolTimeoutMs = 2000;
}

package com.chatagent.config;

import lombok.Getter;
import lombok.Setter;
import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * Agent 配置属性：管理 Agent 执行过程中的各种参数和护栏。
 * 
 * <p>
 * 配置前缀：agent
 * 
 * <p>
 * 执行护栏（Execution Guardrails）：
 * <ul>
 *   <li>maxSteps：推理步数上限，防止无限循环</li>
 *   <li>maxToolCallsPerTurn：每轮工具调用上限，防止单轮滥用</li>
 *   <li>maxToolCallsTotal：全局工具调用上限，防止无限调用</li>
 *   <li>toolTimeoutMs：单工具超时，防止卡死</li>
 *   <li>maxPlanSteps：计划步骤上限，防止计划过于复杂</li>
 *   <li>maxToolRetries：工具重试次数，防止反复失败</li>
 *   <li>minToolIntervalMs：工具调用间隔，防止调用过于频繁</li>
 * </ul>
 * 
 * <p>
 * 分层防护策略：
 * <ul>
 *   <li>每轮限制（perTurn）：只记录警告，不中断</li>
 *   <li>全局限制（total）：抛出异常，终止请求</li>
 *   <li>超时限制（timeout）：返回错误，继续执行其他工具</li>
 *   <li>步数限制（steps）：抛出异常，终止请求</li>
 *   <li>计划步骤限制（planSteps）：降级为直接回答</li>
 *   <li>重试限制（retries）：返回最后一次错误，不再重试</li>
 *   <li>间隔限制（interval）：无限制，仅记录日志</li>
 * </ul>
 * 
 * <p>
 * 可观测性：
 * <ul>
 *   <li>所有护栏触发都有 event=guardrail_hit 日志</li>
 *   <li>带有 traceId 方便追踪</li>
 *   <li>区分不同护栏的 reason</li>
 * </ul>
 */
@Getter
@Setter
@ConfigurationProperties(prefix = "agent")
public class AgentProperties {
    /** 推理超过 8 轮自动终止。抛出异常，终止请求 */
    private int maxSteps = 8;
    /** 每轮模型返回的 tool_calls 最多执行多少个（护栏）。仅记录警告，继续执行 */
    private int maxToolCallsPerTurn = 3;
    /** 一次请求全局最多执行多少个 tool_call（护栏）。抛出异常，终止请求 */
    private int maxToolCallsTotal = 6;
    /** 单个工具执行超时（毫秒）。记录超时错误，继续执行 */
    private long toolTimeoutMs = 2000;
    /** 单次请求的计划步骤上限（护栏）。超出时降级为直接回答 */
    private int maxPlanSteps = 3;
    /** 单个工具执行失败后的最大重试次数（护栏）。超过后不再重试 */
    private int maxToolRetries = 1;
    /** 工具调用最小间隔（毫秒），防止调用过于频繁 */
    private long minToolIntervalMs = 500;
    /** 每个工具名独立的调用总上限（同一请求内），防止单个工具被反复滥用 */
    private int maxToolCallsPerTool = 3;
    /** langchain4j 引擎是否启用 token 级流式输出（失败会回退） */
    private boolean langchainTokenStreamingEnabled = true;
}

/**
 * 执行护栏（Execution Guardrails）设计说明：
 * 
 * - 分层防护：
 *   - 每轮限制（perTurn）：防止单轮滥用
 *   - 全局限制（total）：防止无限循环
 *   - 超时限制（timeout）：防止卡死
 *   - 步数限制（steps）：防止无限推理
 *   - 计划步骤限制（planSteps）：防止计划过于复杂
 *   - 重试限制（retries）：防止工具反复失败
 *   - 间隔限制（interval）：防止调用过于频繁
 * 
 * - 优雅降级：
 *   - 超出每轮限制时：只记录警告，不中断
 *   - 全局限制触发时：抛出异常，终止请求
 *   - 工具超时：返回错误信息，继续执行其他工具
 *   - 计划步骤过多：降级为直接回答，不展示计划
 *   - 工具重试耗尽：返回最后一次错误，不再重试
 * 
 * - 可观测性：
 *   - 所有护栏触发都有 event=guardrail_hit 日志
 *   - 带有 traceId 方便追踪
 *   - 区分不同护栏的 reason
 */

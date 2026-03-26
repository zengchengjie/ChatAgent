package com.chatagent.tools;

import com.fasterxml.jackson.databind.JsonNode;

/**
 * 单个可调用工具接口：Agent 通过工具注册表调用工具。
 * 
 * <p>
 * 实现要求：
 * <ul>
 *   <li>{@link #name()} 与 {@link #toolDefinition()} 中的 name 必须一致</li>
 *   <li>{@link #execute()} 的参数必须与 toolDefinition 中的 parameters 对应</li>
 *   <li>实现类需注册为 Spring Bean，自动进入 {@link ToolRegistry}</li>
 * </ul>
 * 
 * <p>
 * 典型实现：
 * <pre>{@code
 * @Component
 * public class CalculatorTool implements ToolExecutor {
 *     @Override
 *     public String name() { return "calculator"; }
 *     
 *     @Override
 *     public JsonNode toolDefinition() { ... }
 *     
 *     @Override
 *     public String execute(String argumentsJson, String traceId) throws Exception { ... }
 * }
 * }</pre>
 */
public interface ToolExecutor {

    /**
     * 工具名称，必须与 toolDefinition 中的 name 一致。
     * 
     * @return 工具名称
     */
    String name();

    /**
     * OpenAI 兼容的工具定义（JSON 格式）。
     * 
     * <p>
     * 格式：
     * <pre>{@code
     * {
     *   "type": "function",
     *   "function": {
     *     "name": "calculator",
     *     "description": "工具描述",
     *     "parameters": { ... }
     *   }
     * }
     * }</pre>
     * 
     * @return OpenAI tools[] 中的一项
     */
    JsonNode toolDefinition();

    /**
     * 执行工具逻辑。
     * 
     * <p>
     * 参数解析：
     * <ul>
     *   <li>argumentsJson：JSON 格式的参数字符串</li>
     *   <li>traceId：请求追踪 ID，用于日志关联</li>
     * </ul>
     * 
     * <p>
     * 返回值：
     * <ul>
     *   <li>成功：返回工具执行结果（常为 JSON 或简短说明）</li>
     *   <li>失败：返回错误信息字符串</li>
     * </ul>
     * 
     * @param argumentsJson 工具参数（JSON 格式）
     * @param traceId 追踪 ID
     * @return 工具执行结果
     * @throws Exception 工具执行异常
     */
    String execute(String argumentsJson, String traceId) throws Exception;
}

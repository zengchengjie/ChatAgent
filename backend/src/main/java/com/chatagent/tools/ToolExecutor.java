package com.chatagent.tools;

import com.fasterxml.jackson.databind.JsonNode;

/**
 * 单个可调用工具：{@link #toolDefinition()} 与 {@link #execute} 必须同名、参数一致；新增工具时实现本接口并注册为 Bean 即可进入 {@link ToolRegistry}。
 */
public interface ToolExecutor {

    String name();

    /** OpenAI tools[] 中的一项：{@code {"type":"function","function":{name, description, parameters}}} */
    JsonNode toolDefinition();

    /** 模型传入的 function.arguments JSON 字符串；返回给模型的 tool 消息正文（常为 JSON 或简短错误说明）。 */
    String execute(String argumentsJson, String traceId) throws Exception;
}

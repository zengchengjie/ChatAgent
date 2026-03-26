package com.chatagent.tools;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Function;
import java.util.stream.Collectors;
import org.springframework.stereotype.Component;

/**
 * 工具注册表：管理所有可用工具的中央仓库。
 * 
 * <p>
 * 功能：
 * <ul>
 *   <li>自动注册：Spring 注入所有 {@link ToolExecutor} 实现</li>
 *   <li>按名称查找：通过工具名称快速定位执行器</li>
 *   <li>工具定义：生成 OpenAI 兼容的 tools[] 数组</li>
 *   <li>工具执行：调用指定工具的 execute() 方法</li>
 * </ul>
 * 
 * <p>
 * 使用示例：
 * <pre>{@code
 * // 1. 实现工具接口
 * @Component
 * public class CalculatorTool implements ToolExecutor { ... }
 * 
 * // 2. 注入工具注册表
 * @Autowired
 * private ToolRegistry toolRegistry;
 * 
 * // 3. 获取工具定义（发给 LLM）
 * ArrayNode tools = toolRegistry.toolsJson();
 * 
 * // 4. 执行工具
 * String result = toolRegistry.execute("calculator", "{\"expression\":\"1+2\"}", traceId);
 * }</pre>
 */
@Component
public class ToolRegistry {

    private final Map<String, ToolExecutor> byName;
    private final ObjectMapper objectMapper;

    /**
     * 构造工具注册表。
     * 
     * @param executors 所有 {@link ToolExecutor} 实现列表
     * @param objectMapper JSON 序列化器
     */
    public ToolRegistry(List<ToolExecutor> executors, ObjectMapper objectMapper) {
        this.objectMapper = objectMapper;
        this.byName =
                executors.stream()
                        .collect(
                                Collectors.toMap(
                                        ToolExecutor::name, Function.identity(), (a, b) -> a, ConcurrentHashMap::new));
    }

    /**
     * 生成 OpenAI 兼容的 tools[] 数组。
     * 
     * <p>
     * 用于发送给 LLM，告知其可用的工具列表。
     * 
     * @return OpenAI 兼容的 tools[] 数组
     */
    public ArrayNode toolsJson() {
        ArrayNode arr = objectMapper.createArrayNode();
        for (ToolExecutor ex : byName.values()) {
            arr.add(ex.toolDefinition());
        }
        return arr;
    }

    /**
     * 执行指定名称的工具。
     * 
     * @param toolName 工具名称
     * @param argumentsJson 工具参数（JSON 格式）
     * @param traceId 追踪 ID
     * @return 工具执行结果
     * @throws IllegalArgumentException 工具不存在
     * @throws Exception 工具执行异常
     */
    public String execute(String toolName, String argumentsJson, String traceId) throws Exception {
        ToolExecutor ex = byName.get(toolName);
        if (ex == null) {
            throw new IllegalArgumentException("Unknown tool: " + toolName);
        }
        return ex.execute(argumentsJson, traceId);
    }
}

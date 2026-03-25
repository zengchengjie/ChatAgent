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
 * 工具注册表：Spring 注入所有 {@link ToolExecutor}，按 name 映射；{@link #toolsJson()} 即发给模型的 {@code tools} 数组，与执行器一一对应。
 */
@Component
public class ToolRegistry {

    private final Map<String, ToolExecutor> byName;
    private final ObjectMapper objectMapper;

    public ToolRegistry(List<ToolExecutor> executors, ObjectMapper objectMapper) {
        this.objectMapper = objectMapper;
        this.byName =
                executors.stream()
                        .collect(
                                Collectors.toMap(
                                        ToolExecutor::name, Function.identity(), (a, b) -> a, ConcurrentHashMap::new));
    }

    public ArrayNode toolsJson() {
        ArrayNode arr = objectMapper.createArrayNode();
        for (ToolExecutor ex : byName.values()) {
            arr.add(ex.toolDefinition());
        }
        return arr;
    }

    public String execute(String toolName, String argumentsJson, String traceId) throws Exception {
        ToolExecutor ex = byName.get(toolName);
        if (ex == null) {
            throw new IllegalArgumentException("Unknown tool: " + toolName);
        }
        return ex.execute(argumentsJson, traceId);
    }
}

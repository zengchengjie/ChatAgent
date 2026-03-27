package com.chatagent.tools;

import static org.junit.jupiter.api.Assertions.*;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

class ToolRegistryTest {

    private ToolRegistry toolRegistry;
    private MockTool mockTool;

    @BeforeEach
    void setUp() {
        mockTool = new MockTool();
        toolRegistry = new ToolRegistry(List.of(mockTool), new ObjectMapper());
    }

    @Test
    void testExecuteTool() throws Exception {
        String result = toolRegistry.execute("mock_tool", "{\"param\":\"test\"}", "test-trace");
        assertEquals("executed: test", result);
    }

    @Test
    void testExecuteUnknownTool() {
        assertThrows(
                IllegalArgumentException.class,
                () -> toolRegistry.execute("unknown_tool", "{}", "test-trace"));
    }

    @Test
    void testToolsJson() {
        var tools = toolRegistry.toolsJson();
        assertNotNull(tools);
        assertTrue(tools.size() > 0);
    }

    static class MockTool implements ToolExecutor {

        @Override
        public String name() {
            return "mock_tool";
        }

        @Override
        public com.fasterxml.jackson.databind.JsonNode toolDefinition() {
            try {
                return new ObjectMapper()
                        .readTree(
                                """
                        {
                          "type": "function",
                          "function": {
                            "name": "mock_tool",
                            "description": "Mock tool for testing"
                          }
                        }
                        """);
            } catch (Exception e) {
                throw new RuntimeException(e);
            }
        }

        @Override
        public String execute(String argumentsJson, String traceId) throws Exception {
            var node = new ObjectMapper().readTree(argumentsJson);
            String param = node.get("param").asText();
            return "executed: " + param;
        }
    }
}
package com.chatagent.tools;

import static org.junit.jupiter.api.Assertions.*;

import org.junit.jupiter.api.Test;

class CalculatorToolTest {

    @Test
    void testBasicAddition() throws Exception {
        CalculatorTool tool = new CalculatorTool(new com.fasterxml.jackson.databind.ObjectMapper());
        String result = tool.execute("{\"expression\":\"1+2\"}", "test-trace");
        assertEquals("3.0", result);
    }

    @Test
    void testComplexExpression() throws Exception {
        CalculatorTool tool = new CalculatorTool(new com.fasterxml.jackson.databind.ObjectMapper());
        String result = tool.execute("{\"expression\":\"(1+2)*3\"}", "test-trace");
        assertEquals("9.0", result);
    }

    @Test
    void testDivision() throws Exception {
        CalculatorTool tool = new CalculatorTool(new com.fasterxml.jackson.databind.ObjectMapper());
        String result = tool.execute("{\"expression\":\"10/2\"}", "test-trace");
        assertEquals("5.0", result);
    }

    @Test
    void testInvalidCharacters() throws Exception {
        CalculatorTool tool = new CalculatorTool(new com.fasterxml.jackson.databind.ObjectMapper());
        String result = tool.execute("{\"expression\":\"1+abc\"}", "test-trace");
        assertTrue(result.contains("Error"));
    }

    @Test
    void testEmptyExpression() throws Exception {
        CalculatorTool tool = new CalculatorTool(new com.fasterxml.jackson.databind.ObjectMapper());
        String result = tool.execute("{\"expression\":\"\"}", "test-trace");
        assertTrue(result.contains("Error"));
    }

    @Test
    void testToolName() {
        CalculatorTool tool = new CalculatorTool(new com.fasterxml.jackson.databind.ObjectMapper());
        assertEquals("calculator", tool.name());
    }

    @Test
    void testToolDefinition() {
        CalculatorTool tool = new CalculatorTool(new com.fasterxml.jackson.databind.ObjectMapper());
        var definition = tool.toolDefinition();
        assertNotNull(definition);
        assertEquals("function", definition.get("type").asText());
        assertEquals("calculator", definition.get("function").get("name").asText());
    }
}
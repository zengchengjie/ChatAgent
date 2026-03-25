package com.chatagent.tools;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import lombok.RequiredArgsConstructor;
import net.objecthunter.exp4j.Expression;
import net.objecthunter.exp4j.ExpressionBuilder;
import org.springframework.stereotype.Component;

/** 演示用算术工具：仅允许数字与有限运算符，表达式在独立线程求值且 500ms 超时，禁止任意脚本执行。 */
@Component
@RequiredArgsConstructor
public class CalculatorTool implements ToolExecutor {

    private static final java.util.regex.Pattern SAFE =
            java.util.regex.Pattern.compile("^[0-9+\\-*/().\\s]+$");

    private final ObjectMapper objectMapper;
    private final ExecutorService calcPool = Executors.newCachedThreadPool();

    @Override
    public String name() {
        return "calculator";
    }

    @Override
    public JsonNode toolDefinition() {
        try {
            return objectMapper.readTree(
                    """
                    {
                      "type": "function",
                      "function": {
                        "name": "calculator",
                        "description": "Evaluate a numeric arithmetic expression using + - * / and parentheses. Numbers and operators only.",
                        "parameters": {
                          "type": "object",
                          "properties": {
                            "expression": { "type": "string", "description": "Arithmetic expression, e.g. (1+2)*3" }
                          },
                          "required": ["expression"]
                        }
                      }
                    }
                    """);
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }

    @Override
    public String execute(String argumentsJson, String traceId) throws Exception {
        JsonNode args = objectMapper.readTree(argumentsJson == null || argumentsJson.isBlank() ? "{}" : argumentsJson);
        if (!args.has("expression")) {
            return "Error: missing expression";
        }
        String expr = args.get("expression").asText().trim();
        if (expr.isEmpty()) {
            return "Error: empty expression";
        }
        if (!SAFE.matcher(expr).matches()) {
            return "Error: expression contains disallowed characters";
        }
        Future<Double> f =
                calcPool.submit(
                        () -> {
                            Expression e = new ExpressionBuilder(expr).build();
                            return e.evaluate();
                        });
        try {
            double v = f.get(500, TimeUnit.MILLISECONDS);
            if (Double.isNaN(v) || Double.isInfinite(v)) {
                return "Error: invalid numeric result";
            }
            return String.valueOf(v);
        } catch (TimeoutException te) {
            f.cancel(true);
            return "Error: calculator timed out";
        } catch (ExecutionException ee) {
            return "Error: " + ee.getCause().getMessage();
        }
    }
}

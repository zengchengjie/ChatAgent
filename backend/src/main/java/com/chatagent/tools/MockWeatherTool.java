package com.chatagent.tools;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.Locale;
import java.util.Set;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

/** 演示用固定 JSON 天气：城市仅允许白名单内英文名小写键，防止任意长字符串与未定义城市。 */
@Component
@RequiredArgsConstructor
public class MockWeatherTool implements ToolExecutor {

    private static final Set<String> ALLOWED =
            Set.of("beijing", "shanghai", "shenzhen", "guangzhou", "hangzhou", "chengdu");

    private final ObjectMapper objectMapper;

    @Override
    public String name() {
        return "get_mock_weather";
    }

    @Override
    public JsonNode toolDefinition() {
        try {
            return objectMapper.readTree(
                    """
                    {
                      "type": "function",
                      "function": {
                        "name": "get_mock_weather",
                        "description": "Return mock weather for a supported city (demo only).",
                        "parameters": {
                          "type": "object",
                          "properties": {
                            "city": { "type": "string", "description": "City name" }
                          },
                          "required": ["city"]
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
        if (!args.has("city")) {
            return "Error: missing city";
        }
        String city = args.get("city").asText().trim();
        if (city.length() > 64) {
            return "Error: city name too long";
        }
        String key = city.toLowerCase(Locale.ROOT);
        if (!ALLOWED.contains(key)) {
            return "Error: city not in demo whitelist. Allowed: Beijing, Shanghai, Shenzhen, Guangzhou, Hangzhou, Chengdu.";
        }
        return "{\"city\":\"%s\",\"condition\":\"sunny\",\"temperature_c\":22,\"humidity\":55,\"note\":\"mock data\"}"
                .formatted(city.replace("\"", "'"));
    }
}

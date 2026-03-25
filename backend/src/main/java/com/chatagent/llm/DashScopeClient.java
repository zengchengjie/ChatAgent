package com.chatagent.llm;

import com.chatagent.common.ApiException;
import com.chatagent.config.DashScopeProperties;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Service;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientResponseException;

/**
 * 调用阿里云 DashScope「OpenAI 兼容模式」的 Chat Completions：POST {@code /chat/completions}，Bearer 为 {@code DASHSCOPE_API_KEY}。
 *
 * <p>非流式用于 Agent 主循环（解析 {@code tool_calls}）；{@link #streamCompletion} 按行解析 {@code data:} SSE，供需要真实 token 流的场景或测试。
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class DashScopeClient {

    private final RestClient dashScopeRestClient;
    private final HttpClient dashScopeStreamHttpClient;
    private final DashScopeProperties props;
    private final ObjectMapper objectMapper;

    /** 同步请求，解析 {@code choices[0].message} 得到正文与/或 tool_calls。 */
    public AssistantTurn chatCompletion(ArrayNode messages, ArrayNode tools) {
        requireKey();
        ObjectNode body = baseBody(messages, tools, false);
        String json;
        try {
            json = objectMapper.writeValueAsString(body);
        } catch (Exception e) {
            throw new ApiException(HttpStatus.INTERNAL_SERVER_ERROR, "Failed to build request");
        }
        String response;
        try {
            response =
                    dashScopeRestClient
                            .post()
                            .uri("/chat/completions")
                            .header("Authorization", "Bearer " + props.getApiKey())
                            .contentType(MediaType.APPLICATION_JSON)
                            .body(json)
                            .retrieve()
                            .body(String.class);
        } catch (RestClientResponseException e) {
            log.warn("DashScope chat error status={} body={}", e.getStatusCode(), e.getResponseBodyAsString());
            throw new ApiException(
                    HttpStatus.BAD_GATEWAY, "LLM provider error: " + e.getStatusCode().value());
        }
        try {
            return parseAssistantTurn(response);
        } catch (Exception e) {
            log.error("DashScope parse error", e);
            throw new ApiException(HttpStatus.BAD_GATEWAY, "Invalid LLM response");
        }
    }

    /**
     * 流式：读取 {@code text/event-stream}，从每行 JSON 的 {@code choices[0].delta.content} 累积 token（遇 {@code [DONE]} 结束）。
     */
    public void streamCompletion(ArrayNode messages, ArrayNode tools, java.util.function.Consumer<String> onDelta) {
        requireKey();
        ObjectNode body = baseBody(messages, tools, true);
        String payload;
        try {
            payload = objectMapper.writeValueAsString(body);
        } catch (Exception e) {
            throw new ApiException(HttpStatus.INTERNAL_SERVER_ERROR, "Failed to build request");
        }
        URI uri = URI.create(props.getBaseUrl().replaceAll("/$", "") + "/chat/completions");
        HttpRequest req =
                HttpRequest.newBuilder()
                        .uri(uri)
                        .header("Authorization", "Bearer " + props.getApiKey())
                        .header("Content-Type", "application/json")
                        .timeout(Duration.ofMillis(props.getReadTimeoutMs()))
                        .POST(HttpRequest.BodyPublishers.ofString(payload))
                        .build();
        try {
            HttpResponse<java.io.InputStream> resp =
                    dashScopeStreamHttpClient.send(req, HttpResponse.BodyHandlers.ofInputStream());
            if (resp.statusCode() >= 400) {
                String err = new String(resp.body().readAllBytes(), StandardCharsets.UTF_8);
                log.warn("DashScope stream error status={} body={}", resp.statusCode(), err);
                throw new ApiException(HttpStatus.BAD_GATEWAY, "LLM provider error: " + resp.statusCode());
            }
            try (BufferedReader br =
                    new BufferedReader(new InputStreamReader(resp.body(), StandardCharsets.UTF_8))) {
                String line;
                while ((line = br.readLine()) != null) {
                    if (line.isBlank()) {
                        continue;
                    }
                    if (!line.startsWith("data:")) {
                        continue;
                    }
                    String data = line.substring("data:".length()).trim();
                    if ("[DONE]".equals(data)) {
                        break;
                    }
                    JsonNode chunk = objectMapper.readTree(data);
                    JsonNode choices = chunk.get("choices");
                    if (choices == null || !choices.isArray() || choices.isEmpty()) {
                        continue;
                    }
                    JsonNode delta = choices.get(0).get("delta");
                    if (delta != null && delta.has("content") && !delta.get("content").isNull()) {
                        String piece = delta.get("content").asText();
                        if (!piece.isEmpty()) {
                            onDelta.accept(piece);
                        }
                    }
                }
            }
        } catch (ApiException e) {
            throw e;
        } catch (Exception e) {
            log.error("DashScope stream failed", e);
            throw new ApiException(HttpStatus.BAD_GATEWAY, "LLM stream failed");
        }
    }

    /** 构造与 OpenAI Chat Completions 对齐的请求体（model、messages、tools、stream、temperature、max_tokens）。 */
    private ObjectNode baseBody(ArrayNode messages, ArrayNode tools, boolean stream) {
        ObjectNode body = objectMapper.createObjectNode();
        body.put("model", props.getModel());
        body.put("stream", stream);
        body.set("messages", messages);
        body.put("temperature", props.getTemperature());
        body.put("max_tokens", props.getMaxTokens());
        if (tools != null && tools.size() > 0) {
            body.set("tools", tools);
        }
        return body;
    }

    /** 从非流式 JSON 响应提取 assistant 文本、function tool_calls 列表，以及原始 tool_calls 字符串供落库。 */
    private AssistantTurn parseAssistantTurn(String responseJson) throws Exception {
        JsonNode root = objectMapper.readTree(responseJson);
        JsonNode choices = root.get("choices");
        if (choices == null || !choices.isArray() || choices.isEmpty()) {
            return AssistantTurn.builder()
                    .content("")
                    .toolCalls(List.of())
                    .finishReason("stop")
                    .rawToolCallsJson(null)
                    .build();
        }
        JsonNode choice0 = choices.get(0);
        String finishReason =
                choice0.has("finish_reason") && !choice0.get("finish_reason").isNull()
                        ? choice0.get("finish_reason").asText()
                        : "stop";
        JsonNode message = choice0.get("message");
        if (message == null) {
            return AssistantTurn.builder()
                    .content("")
                    .toolCalls(List.of())
                    .finishReason(finishReason)
                    .rawToolCallsJson(null)
                    .build();
        }
        String content =
                message.has("content") && !message.get("content").isNull()
                        ? message.get("content").asText()
                        : "";
        List<ToolCall> calls = new ArrayList<>();
        String rawTools = null;
        if (message.has("tool_calls") && message.get("tool_calls").isArray()) {
            rawTools = objectMapper.writeValueAsString(message.get("tool_calls"));
            for (JsonNode tc : message.get("tool_calls")) {
                String id = tc.has("id") ? tc.get("id").asText() : "";
                JsonNode fn = tc.get("function");
                if (fn == null) {
                    continue;
                }
                String name = fn.has("name") ? fn.get("name").asText() : "";
                String args = fn.has("arguments") && !fn.get("arguments").isNull() ? fn.get("arguments").asText() : "{}";
                calls.add(ToolCall.builder().id(id).name(name).argumentsJson(args).build());
            }
        }
        return AssistantTurn.builder()
                .content(content)
                .toolCalls(calls)
                .finishReason(finishReason)
                .rawToolCallsJson(rawTools)
                .build();
    }

    private void requireKey() {
        if (props.getApiKey() == null || props.getApiKey().isBlank()) {
            throw new ApiException(HttpStatus.SERVICE_UNAVAILABLE, "DASHSCOPE_API_KEY is not configured");
        }
    }
}

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
 * DashScope LLM 客户端：调用阿里云 DashScope 的 Chat Completions API。
 * 
 * <p>
 * 功能：
 * <ul>
 *   <li>同步调用：用于 Agent 主循环（解析 tool_calls）</li>
 *   <li>流式调用：支持 SSE 流式输出（逐 token 推送）</li>
 *   <li>OpenAI 兼容：使用 DashScope 的 OpenAI 兼容模式</li>
 *   <li>统一鉴权：通过 Bearer Token 认证</li>
 * </ul>
 * 
 * <p>
 * 使用场景：
 * <ul>
 *   <li>chatCompletion：Agent 推理循环（需要解析 tool_calls）</li>
 *   <li>streamCompletion：前端流式对话（需要实时显示）</li>
 * </ul>
 * 
 * <p>
 * 配置要求：
 * <ul>
 *   <li>DASHSCOPE_API_KEY：阿里云 DashScope API Key</li>
 *   <li>baseUrl：DashScope 服务地址</li>
 *   <li>model：使用的模型名称</li>
 *   <li>temperature：生成温度参数</li>
 *   <li>maxTokens：最大输出 token 数</li>
 * </ul>
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class DashScopeClient {

    private final RestClient dashScopeRestClient;
    private final HttpClient dashScopeStreamHttpClient;
    private final DashScopeProperties props;
    private final ObjectMapper objectMapper;

    /**
     * 同步调用 LLM，解析响应中的 content 和 tool_calls。
     * 
     * <p>
     * 用于 Agent 推理循环，需要解析模型返回的 tool_calls 以决定是否执行工具。
     * 
     * @param messages 对话消息列表（OpenAI 格式）
     * @param tools 可用工具列表（OpenAI tools[] 格式）
     * @return 包含 content、tool_calls 和 finishReason 的响应对象
     */
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
     * 流式调用 LLM，逐 token 推送内容。
     * 
     * <p>
     * 用于前端流式对话，通过 SSE 实时推送每个 token。
     * 
     * @param messages 对话消息列表（OpenAI 格式）
     * @param tools 可用工具列表（OpenAI tools[] 格式）
     * @param onDelta 每个 token 的回调函数
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

    /** Same as {@link #chatCompletion(ArrayNode, ArrayNode)} but override model name for this request. */
    public AssistantTurn chatCompletion(ArrayNode messages, ArrayNode tools, String modelOverride) {
        requireKey();
        ObjectNode body = baseBody(messages, tools, false);
        if (modelOverride != null && !modelOverride.isBlank()) {
            body.put("model", modelOverride);
        }
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

    /** Same as {@link #streamCompletion(ArrayNode, ArrayNode, java.util.function.Consumer)} but override model name. */
    public void streamCompletion(
            ArrayNode messages,
            ArrayNode tools,
            String modelOverride,
            java.util.function.Consumer<String> onDelta) {
        requireKey();
        ObjectNode body = baseBody(messages, tools, true);
        if (modelOverride != null && !modelOverride.isBlank()) {
            body.put("model", modelOverride);
        }
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

    /**
     * 构造 OpenAI 兼容的请求体。
     * 
     * @param messages 对话消息列表
     * @param tools 可用工具列表
     * @param stream 是否流式请求
     * @return 请求体 JSON 对象
     */
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

    /**
     * 解析同步调用的响应，提取 content 和 tool_calls。
     * 
     * @param responseJson 原始响应 JSON
     * @return 包含 content、tool_calls 和原始 tool_calls JSON 的对象
     * @throws Exception 解析异常
     */
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

    /**
     * 检查 API Key 是否配置。
     * 
     * @throws ApiException API Key 未配置时抛出
     */
    private void requireKey() {
        if (props.getApiKey() == null || props.getApiKey().isBlank()) {
            throw new ApiException(HttpStatus.SERVICE_UNAVAILABLE, "DASHSCOPE_API_KEY is not configured");
        }
    }
}

package com.chatagent.llm;

import com.chatagent.common.ApiException;
import com.chatagent.config.DashScopeProperties;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
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
 * DashScope OpenAI 兼容模式 embeddings：POST {@code /embeddings} 并解析 {@code data[0].embedding}。
 *
 * <p>使用兼容模式而不是原生 embeddings API，便于统一鉴权与请求构造方式。
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class DashScopeEmbeddingClient {

    private static final String EMBEDDING_MODEL = "text-embedding-v3";

    private final RestClient dashScopeRestClient;
    private final DashScopeProperties props;
    private final ObjectMapper objectMapper;

    /** @return embedding 向量（double[]），用于后续余弦相似度计算。 */
    public double[] embed(String input) {
        requireKey();
        if (input == null || input.isBlank()) {
            return new double[0];
        }

        var body = objectMapper.createObjectNode();
        body.put("model", EMBEDDING_MODEL);
        body.put("input", input);

        String json;
        try {
            json = objectMapper.writeValueAsString(body);
        } catch (Exception e) {
            throw new ApiException(HttpStatus.INTERNAL_SERVER_ERROR, "Failed to build embeddings request");
        }

        String response;
        try {
            response =
                    dashScopeRestClient
                            .post()
                            .uri("/embeddings")
                            .header("Authorization", "Bearer " + props.getApiKey())
                            .contentType(MediaType.APPLICATION_JSON)
                            .body(json)
                            .retrieve()
                            .body(String.class);
        } catch (RestClientResponseException e) {
            log.warn(
                    "DashScope embeddings error status={} body={}",
                    e.getStatusCode(),
                    e.getResponseBodyAsString());
            throw new ApiException(
                    HttpStatus.BAD_GATEWAY, "Embeddings provider error: " + e.getStatusCode().value());
        }

        try {
            JsonNode root = objectMapper.readTree(response);
            JsonNode data = root.get("data");
            if (data == null || !data.isArray() || data.isEmpty()) {
                throw new IllegalStateException("Missing data array in embeddings response");
            }
            JsonNode embNode = data.get(0).get("embedding");
            if (embNode == null || !embNode.isArray()) {
                throw new IllegalStateException("Missing embedding array in embeddings response");
            }

            List<Double> values = new ArrayList<>(embNode.size());
            for (JsonNode v : embNode) {
                values.add(v.asDouble());
            }
            double[] arr = new double[values.size()];
            for (int i = 0; i < values.size(); i++) {
                arr[i] = values.get(i);
            }
            return arr;
        } catch (ApiException e) {
            throw e;
        } catch (Exception e) {
            log.error("DashScope embeddings parse error. response={}", response, e);
            throw new ApiException(HttpStatus.BAD_GATEWAY, "Invalid embeddings response");
        }
    }

    private void requireKey() {
        if (props.getApiKey() == null || props.getApiKey().isBlank()) {
            throw new ApiException(HttpStatus.SERVICE_UNAVAILABLE, "DASHSCOPE_API_KEY is not configured");
        }
    }
}


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
 * DashScope 嵌入客户端：调用阿里云 DashScope 的 Embeddings API。
 * 
 * <p>
 * 功能：
 * <ul>
 *   <li>向量生成：将文本转换为高维向量表示</li>
 *   <li>余弦相似度：用于 RAG 检索时的相似度计算</li>
 *   <li>OpenAI 兼容：使用 DashScope 的 OpenAI 兼容模式</li>
 *   <li>统一鉴权：通过 Bearer Token 认证</li>
 * </ul>
 * 
 * <p>
 * 使用场景：
 * <ul>
 *   <li>知识库索引：将文档转换为向量存储</li>
 *   <li>查询向量化：将用户查询转换为向量进行相似度匹配</li>
 *   <li>RAG 检索：基于向量相似度检索相关文档片段</li>
 * </ul>
 * 
 * <p>
 * 配置要求：
 * <ul>
 *   <li>DASHSCOPE_API_KEY：阿里云 DashScope API Key</li>
 *   <li>baseUrl：DashScope 服务地址</li>
 * </ul>
 * 
 * <p>
 * 模型信息：
 * <ul>
 *   <li>模型名称：text-embedding-v3</li>
 *   <li>向量维度：1536 维（OpenAI 兼容）</li>
 *   <li>输入长度：单次最多 2048 tokens</li>
 * </ul>
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class DashScopeEmbeddingClient {

    private static final String EMBEDDING_MODEL = "text-embedding-v3";

    private final RestClient dashScopeRestClient;
    private final DashScopeProperties props;
    private final ObjectMapper objectMapper;

    /**
     * 生成文本的嵌入向量。
     * 
     * <p>
     * 用于 RAG 场景：
     * <ul>
     *   <li>索引阶段：将文档转换为向量存储</li>
     *   <li>查询阶段：将用户查询转换为向量进行相似度匹配</li>
     * </ul>
     * 
     * @param input 输入文本
     * @return 嵌入向量（double[]），长度为 1536
     * @throws ApiException API 调用失败或解析异常时抛出
     */
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

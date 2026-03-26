package com.chatagent.tools;

import com.chatagent.knowledge.KnowledgeService;
import com.chatagent.knowledge.KnowledgeService.KnowledgeChunkResult;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.util.List;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

/**
 * 知识库搜索工具：基于向量相似度检索本地知识库中的相关片段。
 * 
 * <p>
 * 功能：
 * <ul>
 *   <li>向量搜索：使用嵌入向量计算查询与文档的相似度</li>
 *   <li>Top-K 检索：返回最相关的 K 个片段</li>
 *   <li>相似度过滤：通过 minScore 过滤低相关度结果</li>
 *   <li>文档过滤：通过 docTitleFilter 限定搜索范围</li>
 *   <li>结果压缩：自动压缩输出以控制工具调用响应大小</li>
 * </ul>
 * 
 * <p>
 * 使用示例：
 * <pre>{@code
 * // 搜索知识库
 * String result = execute(
 *     "{\"query\":\"如何使用计算器\",\"k\":3,\"minScore\":0.5}",
 *     traceId
 * );
 * // 返回: {"hit":true,"query":"...","k":3,"minScore":0.5,"chunks":[...]}
 * }</pre>
 * 
 * <p>
 * 参数说明：
 * <ul>
 *   <li>query：搜索查询（必填）</li>
 *   <li>k：返回结果数量（1-5，默认 3）</li>
 *   <li>minScore：最小相似度阈值（0-1，默认 0）</li>
 *   <li>docTitleFilter：文档标题过滤器（可选）</li>
 * </ul>
 */
@Component
@RequiredArgsConstructor
public class SearchKnowledgeTool implements ToolExecutor {

    private static final int DEFAULT_K = 3;
    private static final int MAX_K = 5;
    private static final double DEFAULT_MIN_SCORE = 0.0;
    private static final int MAX_TOOL_OUTPUT_CHARS = 3500;
    private static final int MAX_TEXT_PER_CHUNK_CHARS = 800;
    private static final int MAX_QUERY_CHARS = 800;
    private static final int MAX_DOC_FILTER_CHARS = 64;

    private final ObjectMapper objectMapper;
    private final KnowledgeService knowledgeService;

    @Override
    public String name() {
        return "search_knowledge";
    }

    @Override
    public JsonNode toolDefinition() {
        try {
            return objectMapper.readTree(
                    """
                    {
                      "type": "function",
                      "function": {
                        "name": "search_knowledge",
                        "description": "Search local knowledge base (backend/src/main/resources/knowledge/*.md) and return the most relevant markdown chunks.",
                        "parameters": {
                          "type": "object",
                          "properties": {
                            "query": { "type": "string", "description": "Question or search keywords" },
                            "k": { "type": "integer", "description": "Top-k chunks to return (1-5)" },
                            "minScore": { "type": "number", "description": "Minimum cosine similarity score to keep a chunk (0-1)" },
                            "docTitleFilter": { "type": "string", "description": "Optional substring filter on docTitle (coarse pre-filter)" }
                          },
                          "required": ["query"]
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
        JsonNode args =
                objectMapper.readTree(
                        argumentsJson == null || argumentsJson.isBlank() ? "{}" : argumentsJson);

        if (!args.has("query")) {
            return "Error: missing query";
        }
        String query = args.get("query").asText().trim();
        if (query.isEmpty()) {
            return "Error: empty query";
        }
        if (query.length() > MAX_QUERY_CHARS) {
            return "Error: query too long";
        }

        int k = args.has("k") ? args.get("k").asInt(DEFAULT_K) : DEFAULT_K;
        k = Math.max(1, Math.min(k, MAX_K));

        double minScore =
                args.has("minScore") ? args.get("minScore").asDouble(DEFAULT_MIN_SCORE) : DEFAULT_MIN_SCORE;
        if (Double.isNaN(minScore) || Double.isInfinite(minScore)) {
            minScore = DEFAULT_MIN_SCORE;
        }
        minScore = Math.max(0.0, Math.min(minScore, 1.0));

        String docTitleFilter = args.has("docTitleFilter") ? args.get("docTitleFilter").asText("").trim() : "";
        if (docTitleFilter.length() > MAX_DOC_FILTER_CHARS) {
            docTitleFilter = docTitleFilter.substring(0, MAX_DOC_FILTER_CHARS);
        }
        if (docTitleFilter.isBlank()) {
            docTitleFilter = null;
        }

        List<KnowledgeChunkResult> chunks = knowledgeService.search(query, k, minScore, docTitleFilter);
        return buildToolOutput(query, k, minScore, chunks, MAX_TEXT_PER_CHUNK_CHARS);
    }

    private String buildToolOutput(
            String query, int k, double minScore, List<KnowledgeChunkResult> chunks, int maxTextPerChunkChars)
            throws Exception {
        int perChunk = maxTextPerChunkChars;
        while (perChunk >= 200) {
            ObjectNode out = objectMapper.createObjectNode();
            out.put("hit", !chunks.isEmpty());
            out.put("query", query);
            out.put("k", k);
            out.put("minScore", minScore);
            ArrayNode arr = objectMapper.createArrayNode();
            for (KnowledgeChunkResult c : chunks) {
                ObjectNode n = objectMapper.createObjectNode();
                n.put("chunkId", c.chunkId());
                n.put("docTitle", c.docTitle());
                n.put("text", truncate(c.text(), perChunk));
                n.put("score", c.score());
                arr.add(n);
            }
            out.set("chunks", arr);
            String json = objectMapper.writeValueAsString(out);
            if (json.length() <= MAX_TOOL_OUTPUT_CHARS) {
                return json;
            }
            perChunk = perChunk / 2;
        }

        // 最后兜底：至少返回结构但文本极短，保证 JSON 不会被截断成非法。
        ObjectNode out = objectMapper.createObjectNode();
        out.put("hit", !chunks.isEmpty());
        out.put("query", query);
        out.put("k", k);
        out.put("minScore", minScore);
        ArrayNode arr = objectMapper.createArrayNode();
        for (KnowledgeChunkResult c : chunks) {
            ObjectNode n = objectMapper.createObjectNode();
            n.put("chunkId", c.chunkId());
            n.put("docTitle", c.docTitle());
            n.put("text", truncate(c.text(), 120));
            n.put("score", c.score());
            arr.add(n);
        }
        out.set("chunks", arr);
        return objectMapper.writeValueAsString(out);
    }

    private static String truncate(String s, int max) {
        if (s == null) {
            return "";
        }
        if (s.length() <= max) {
            return s;
        }
        return s.substring(0, max) + "...";
    }
}

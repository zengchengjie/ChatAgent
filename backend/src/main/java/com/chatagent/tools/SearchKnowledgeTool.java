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

@Component
@RequiredArgsConstructor
public class SearchKnowledgeTool implements ToolExecutor {

    private static final int DEFAULT_K = 3;
    private static final int MAX_K = 5;
    private static final int MAX_TOOL_OUTPUT_CHARS = 3500;
    private static final int MAX_TEXT_PER_CHUNK_CHARS = 800;

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
                            "k": { "type": "integer", "description": "Top-k chunks to return (1-5)" }
                          },
                          "required": ["query", "k"]
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

        int k = args.has("k") ? args.get("k").asInt(DEFAULT_K) : DEFAULT_K;
        k = Math.max(1, Math.min(k, MAX_K));

        List<KnowledgeChunkResult> chunks = knowledgeService.search(query, k);
        return buildToolOutput(chunks, MAX_TEXT_PER_CHUNK_CHARS);
    }

    private String buildToolOutput(List<KnowledgeChunkResult> chunks, int maxTextPerChunkChars)
            throws Exception {
        int perChunk = maxTextPerChunkChars;
        while (perChunk >= 200) {
            ObjectNode out = objectMapper.createObjectNode();
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


package com.chatagent.knowledge;

import com.chatagent.llm.DashScopeEmbeddingClient;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

@Service
@RequiredArgsConstructor
@Slf4j
public class KnowledgeService {

    private final JdbcTemplate jdbcTemplate;
    private final DashScopeEmbeddingClient embeddingClient;
    private final ObjectMapper objectMapper;

    public record KnowledgeChunkResult(long chunkId, String docTitle, String text, double score) {}

    public List<KnowledgeChunkResult> search(String query, int k) {
        if (query == null || query.isBlank()) {
            return List.of();
        }
        int safeK = Math.max(1, Math.min(k, 10));

        double[] qEmb = embeddingClient.embed(query);
        if (qEmb.length == 0) {
            return List.of();
        }

        List<KnowledgeChunkResult> scored = new ArrayList<>();
        jdbcTemplate.query(
                "SELECT id, doc_title, chunk_text, embedding_json FROM knowledge_chunks",
                rs -> {
                    long id = rs.getLong("id");
                    String docTitle = rs.getString("doc_title");
                    String text = rs.getString("chunk_text");
                    String embeddingJson = rs.getString("embedding_json");
                    double[] emb;
                    try {
                        emb = objectMapper.readValue(embeddingJson, double[].class);
                    } catch (Exception e) {
                        throw new RuntimeException("Failed to parse embedding_json for chunkId=" + id, e);
                    }
                    double score = cosineSimilarity(qEmb, emb);
                    scored.add(new KnowledgeChunkResult(id, docTitle, text, score));
                });

        scored.sort(Comparator.comparingDouble(KnowledgeChunkResult::score).reversed());
        if (scored.size() <= safeK) {
            return scored;
        }
        return scored.subList(0, safeK);
    }

    private static double cosineSimilarity(double[] a, double[] b) {
        if (a == null || b == null || a.length == 0 || b.length == 0) {
            return 0;
        }
        int n = Math.min(a.length, b.length);
        double dot = 0;
        double normA = 0;
        double normB = 0;
        for (int i = 0; i < n; i++) {
            dot += a[i] * b[i];
            normA += a[i] * a[i];
            normB += b[i] * b[i];
        }
        if (normA == 0 || normB == 0) {
            return 0;
        }
        return dot / (Math.sqrt(normA) * Math.sqrt(normB));
    }
}


package com.chatagent.knowledge;

import com.chatagent.llm.DashScopeEmbeddingClient;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
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

    public List<KnowledgeChunkResult> search(String query, int k, double minScore, String docTitleFilter) {
        if (query == null || query.isBlank()) {
            return List.of();
        }
        int safeK = Math.max(1, Math.min(k, 10));
        double safeMinScore = Double.isNaN(minScore) || Double.isInfinite(minScore) ? 0.0 : minScore;

        double[] qEmb = embeddingClient.embed(query);
        if (qEmb.length == 0) {
            return List.of();
        }

        List<KnowledgeChunkResult> scored = new ArrayList<>();
        String sql = "SELECT id, doc_title, chunk_text, embedding_json FROM knowledge_chunks";
        Object[] args = new Object[] {};
        if (docTitleFilter != null && !docTitleFilter.isBlank()) {
            // coarse filter: simple substring match
            sql += " WHERE lower(doc_title) LIKE ?";
            args = new Object[] {"%" + docTitleFilter.toLowerCase(Locale.ROOT) + "%"};
        }

        Set<Long> seenChunkIds = new HashSet<>();
        Set<String> seenTextKeys = new HashSet<>();
        if (args.length == 0) {
            jdbcTemplate.query(
                    sql,
                    rs -> {
                        long id = rs.getLong("id");
                        if (!seenChunkIds.add(id)) {
                            return;
                        }
                        String docTitle = rs.getString("doc_title");
                        String text = rs.getString("chunk_text");
                        if (text == null) {
                            return;
                        }
                        String key = (docTitle != null ? docTitle : "") + ":" + text.trim();
                        if (!seenTextKeys.add(key)) {
                            return;
                        }
                        String embeddingJson = rs.getString("embedding_json");
                        double[] emb;
                        try {
                            emb = objectMapper.readValue(embeddingJson, double[].class);
                        } catch (Exception e) {
                            // 必须跳过，不能导致整个请求失败
                            log.warn("event=knowledge_embedding_parse_failed chunkId={} err={}", id, e.toString());
                            return;
                        }
                        double score = cosineSimilarity(qEmb, emb);
                        if (score < safeMinScore) {
                            return;
                        }
                        scored.add(new KnowledgeChunkResult(id, docTitle, text, score));
                    });
        } else {
            jdbcTemplate.query(
                    sql,
                    rs -> {
                    long id = rs.getLong("id");
                    if (!seenChunkIds.add(id)) {
                        return;
                    }
                    String docTitle = rs.getString("doc_title");
                    String text = rs.getString("chunk_text");
                    if (text == null) {
                        return;
                    }
                    String key = (docTitle != null ? docTitle : "") + ":" + text.trim();
                    if (!seenTextKeys.add(key)) {
                        return;
                    }
                    String embeddingJson = rs.getString("embedding_json");
                    double[] emb;
                    try {
                        emb = objectMapper.readValue(embeddingJson, double[].class);
                    } catch (Exception e) {
                        // 必须跳过，不能导致整个请求失败
                        log.warn("event=knowledge_embedding_parse_failed chunkId={} err={}", id, e.toString());
                        return;
                    }
                    double score = cosineSimilarity(qEmb, emb);
                    if (score < safeMinScore) {
                        return;
                    }
                    scored.add(new KnowledgeChunkResult(id, docTitle, text, score));
                    },
                    args);
        }

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


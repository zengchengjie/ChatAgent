package com.chatagent.knowledge;

import com.chatagent.common.ApiException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import org.springframework.core.io.Resource;
import org.springframework.core.io.ResourceLoader;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
@RequiredArgsConstructor
public class RagEvalService {

    private final KnowledgeService knowledgeService;
    private final JdbcTemplate jdbcTemplate;
    private final ObjectMapper objectMapper;
    private final ResourceLoader resourceLoader;

    public record EvalRunResult(
            String id,
            int k,
            double minScore,
            int caseCount,
            int hits,
            double recallAtK,
            double mrr,
            double avgLatencyMs,
            String createdAt) {}

    public record EvalRunRequest(int k, double minScore) {}

    @Transactional
    public EvalRunResult run(EvalRunRequest req) {
        int k = Math.max(1, Math.min(req.k(), 10));
        double minScore = Double.isFinite(req.minScore()) ? req.minScore() : 0.0;
        List<EvalCase> cases = loadCases();
        if (cases.isEmpty()) {
            throw new ApiException(HttpStatus.BAD_REQUEST, "No eval cases configured");
        }

        String runId = UUID.randomUUID().toString();
        int hits = 0;
        double mrrSum = 0.0;
        long latencySum = 0;

        for (EvalCase c : cases) {
            long t0 = System.currentTimeMillis();
            List<KnowledgeService.KnowledgeChunkResult> res = knowledgeService.search(c.query, k, minScore, null);
            long ms = System.currentTimeMillis() - t0;
            latencySum += ms;

            Integer foundRank = null;
            Double foundScore = null;
            if (c.expectedDocTitle != null && !c.expectedDocTitle.isBlank()) {
                for (int i = 0; i < res.size(); i++) {
                    KnowledgeService.KnowledgeChunkResult r = res.get(i);
                    if (r.docTitle() != null && r.docTitle().equalsIgnoreCase(c.expectedDocTitle.trim())) {
                        foundRank = i + 1;
                        foundScore = r.score();
                        break;
                    }
                }
            }

            if (foundRank != null) {
                hits++;
                mrrSum += 1.0 / foundRank;
            }

            jdbcTemplate.update(
                    """
                    INSERT INTO rag_eval_case_results(run_id, query, expected_doc_title, found_rank, found_score, latency_ms)
                    VALUES (?,?,?,?,?,?)
                    """,
                    runId,
                    c.query,
                    c.expectedDocTitle,
                    foundRank,
                    foundScore,
                    ms);
        }

        int caseCount = cases.size();
        double recallAtK = (double) hits / caseCount;
        double mrr = mrrSum / caseCount;
        double avgLatencyMs = (double) latencySum / caseCount;

        jdbcTemplate.update(
                """
                INSERT INTO rag_eval_runs(id, k, min_score, case_count, hits, recall_at_k, mrr, avg_latency_ms)
                VALUES (?,?,?,?,?,?,?,?)
                """,
                runId,
                k,
                minScore,
                caseCount,
                hits,
                recallAtK,
                mrr,
                avgLatencyMs);

        String createdAt =
                jdbcTemplate.queryForObject("SELECT created_at FROM rag_eval_runs WHERE id=?", String.class, runId);
        return new EvalRunResult(runId, k, minScore, caseCount, hits, recallAtK, mrr, avgLatencyMs, createdAt);
    }

    public List<EvalRunResult> listRuns(int limit) {
        int safeLimit = Math.max(1, Math.min(limit, 50));
        return jdbcTemplate.query(
                """
                SELECT id, k, min_score, case_count, hits, recall_at_k, mrr, avg_latency_ms, created_at
                  FROM rag_eval_runs
                 ORDER BY created_at DESC
                 LIMIT ?
                """,
                (rs, i) ->
                        new EvalRunResult(
                                rs.getString("id"),
                                rs.getInt("k"),
                                rs.getDouble("min_score"),
                                rs.getInt("case_count"),
                                rs.getInt("hits"),
                                rs.getDouble("recall_at_k"),
                                rs.getDouble("mrr"),
                                rs.getDouble("avg_latency_ms"),
                                rs.getString("created_at")),
                safeLimit);
    }

    private record EvalCase(String query, String expectedDocTitle) {}

    private List<EvalCase> loadCases() {
        try {
            Resource r = resourceLoader.getResource("classpath:rag/rag-eval-set.json");
            try (InputStream is = r.getInputStream()) {
                String json = new String(is.readAllBytes(), StandardCharsets.UTF_8);
                JsonNode arr = objectMapper.readTree(json);
                if (arr == null || !arr.isArray()) {
                    return List.of();
                }
                List<EvalCase> out = new ArrayList<>();
                for (JsonNode n : arr) {
                    String q = n.has("query") ? n.get("query").asText() : null;
                    String expected = n.has("expectedDocTitle") ? n.get("expectedDocTitle").asText() : null;
                    if (q == null || q.isBlank()) {
                        continue;
                    }
                    out.add(new EvalCase(q, expected));
                }
                return out;
            }
        } catch (Exception e) {
            return List.of();
        }
    }
}


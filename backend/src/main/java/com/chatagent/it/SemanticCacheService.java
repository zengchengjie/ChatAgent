package com.chatagent.it;

import dev.langchain4j.model.embedding.EmbeddingModel;
import io.opentelemetry.api.trace.Span;
import io.opentelemetry.api.trace.Tracer;
import io.opentelemetry.context.Scope;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CopyOnWriteArrayList;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

@Service
@RequiredArgsConstructor
public class SemanticCacheService {

    private final EmbeddingModel embeddingModel;
    private final ITSupportProperties properties;
    private final Tracer tracer;

    private final CopyOnWriteArrayList<CacheEntry> cache = new CopyOnWriteArrayList<>();

    public CacheHit findBestMatch(String question) {
        Span span = tracer.spanBuilder("semantic_cache.find").startSpan();
        try (Scope scope = span.makeCurrent()) {
            span.setAttribute("question.length", question.length());
            double[] queryVector = embedding(question);
            if (queryVector.length == 0 || cache.isEmpty()) {
                span.setAttribute("cache.hit", false);
                span.setAttribute("cache.reason", "empty");
                return CacheHit.miss(queryVector);
            }

            CacheEntry best = cache.stream()
                    .max(Comparator.comparingDouble(entry -> cosineSimilarity(queryVector, entry.vector())))
                    .orElse(null);
            if (best == null) {
                span.setAttribute("cache.hit", false);
                return CacheHit.miss(queryVector);
            }
            double similarity = cosineSimilarity(queryVector, best.vector());
            span.setAttribute("cache.similarity", similarity);
            if (similarity >= properties.getSemanticCacheSimilarityThreshold()) {
                span.setAttribute("cache.hit", true);
                return CacheHit.hit(best.answer(), queryVector, similarity);
            }
            span.setAttribute("cache.hit", false);
            span.setAttribute("cache.reason", "below_threshold");
            return CacheHit.miss(queryVector);
        } finally {
            span.end();
        }
    }

    public void put(String question, String answer, double[] vector) {
        if (vector.length == 0) {
            return;
        }
        cache.add(new CacheEntry(UUID.randomUUID().toString(), question, answer, vector, Instant.now()));
        if (cache.size() > 500) {
            List<CacheEntry> snapshot = new ArrayList<>(cache);
            snapshot.sort(Comparator.comparing(CacheEntry::createdAt));
            cache.remove(snapshot.get(0));
        }
    }

    private double[] embedding(String text) {
        try {
            float[] vec = embeddingModel.embed(text).content().vector();
            double[] result = new double[vec.length];
            for (int i = 0; i < vec.length; i++) result[i] = vec[i];
            return result;
        } catch (Exception e) {
            return new double[0];
        }
    }

    private double cosineSimilarity(double[] left, double[] right) {
        if (left.length == 0 || right.length == 0 || left.length != right.length) {
            return 0.0;
        }
        double dot = 0.0;
        double leftNorm = 0.0;
        double rightNorm = 0.0;
        for (int i = 0; i < left.length; i++) {
            dot += left[i] * right[i];
            leftNorm += left[i] * left[i];
            rightNorm += right[i] * right[i];
        }
        if (leftNorm == 0.0 || rightNorm == 0.0) {
            return 0.0;
        }
        return dot / (Math.sqrt(leftNorm) * Math.sqrt(rightNorm));
    }

    private record CacheEntry(String id, String question, String answer, double[] vector, Instant createdAt) {}

    public record CacheHit(boolean hit, String answer, double[] queryVector, double similarity) {

        static CacheHit hit(String answer, double[] queryVector, double similarity) {
            return new CacheHit(true, answer, queryVector, similarity);
        }

        static CacheHit miss(double[] queryVector) {
            return new CacheHit(false, null, queryVector, 0.0);
        }
    }
}

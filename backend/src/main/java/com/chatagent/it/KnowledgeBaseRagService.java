package com.chatagent.it;

import com.chatagent.common.ApiException;
import dev.langchain4j.data.segment.TextSegment;
import dev.langchain4j.model.embedding.EmbeddingModel;
import dev.langchain4j.store.embedding.redis.RedisEmbeddingStore;
import dev.langchain4j.store.embedding.EmbeddingMatch;
import dev.langchain4j.store.embedding.EmbeddingSearchRequest;
import io.opentelemetry.api.trace.Span;
import io.opentelemetry.api.trace.Tracer;
import io.opentelemetry.context.Scope;
import jakarta.annotation.PostConstruct;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;

@Service
@RequiredArgsConstructor
@Slf4j
public class KnowledgeBaseRagService {

    private final EmbeddingModel embeddingModel;
    @Qualifier("itSupportEmbeddingStore")
    private final RedisEmbeddingStore embeddingStore;
    private final ITSupportProperties properties;
    private final Tracer tracer;

    private volatile boolean indexed = false;

    @PostConstruct
    public void initialize() {
        loadAndIndex();
    }

    public synchronized void loadAndIndex() {
        try {
            Path knowledgeFile = resolveKnowledgeFile();
            if (!Files.exists(knowledgeFile)) {
                log.warn("Knowledge base file not found: {}", knowledgeFile.toAbsolutePath());
                return;
            }

            String markdown = Files.readString(knowledgeFile, StandardCharsets.UTF_8);
            List<String> chunks = splitMarkdown(markdown);
            int successCount = 0;
            for (String chunk : chunks) {
                try {
                    TextSegment segment = TextSegment.from(chunk);
                    embeddingStore.add(embeddingModel.embed(segment).content(), segment);
                    successCount++;
                } catch (Exception e) {
                    log.warn("Failed to embed chunk, skipping: {}", e.getMessage());
                }
            }
            indexed = successCount > 0;
            log.info("Indexed IT knowledge base chunks={}/{}", successCount, chunks.size());
        } catch (Exception e) {
            log.error("Failed to load and index knowledge base", e);
            // Do not throw to allow application startup
            log.warn("RAG indexing failed, search will return placeholder.");
        }
    }

    public String search(String query) {
        Span span = tracer.spanBuilder("rag.search").startSpan();
        try (Scope scope = span.makeCurrent()) {
            span.setAttribute("query", query);
            if (!indexed) {
                // In dev, the embedding calls during @PostConstruct may be slow or fail due to config;
                // try to (re)index lazily on first search.
                loadAndIndex();
                if (!indexed) {
                    span.setAttribute("indexed", false);
                    return "知识库尚未初始化完成，请稍后重试。";
                }
            }
            span.setAttribute("indexed", true);

            List<EmbeddingMatch<TextSegment>> matches;
            try {
                var queryEmbedding = embeddingModel.embed(query).content();
                var searchRequest = EmbeddingSearchRequest.builder()
                        .queryEmbedding(queryEmbedding)
                        .maxResults(properties.getRagTopK())
                        .minScore(properties.getRagMinScore())
                        .build();
                matches = embeddingStore.search(searchRequest).matches();
            } catch (Exception e) {
                log.warn("Failed to embed query for RAG search: {}", e.getMessage());
                matches = List.of();
            }
            span.setAttribute("match_count", matches.size());
            if (matches.isEmpty()) {
                return "知识库中暂未检索到相关内容。";
            }

            StringBuilder result = new StringBuilder();
            java.util.Set<String> seen = new java.util.HashSet<>();
            for (EmbeddingMatch<TextSegment> match : matches) {
                String text = match.embedded().text().trim();
                if (text.isBlank() || !seen.add(text)) {
                    continue; // 去重
                }
                result.append(text).append("\n---\n");
            }
            return result.toString().trim();
        } finally {
            span.end();
        }
    }

    private List<String> splitMarkdown(String markdown) {
        String normalized = markdown == null ? "" : markdown.trim();
        if (normalized.isBlank()) {
            return List.of("知识库为空");
        }
        String[] parts = normalized.split("(?m)^##\\s+");
        List<String> chunks = new ArrayList<>();
        for (String part : parts) {
            String chunk = part.trim();
            // 跳过空白或只有标题的 chunk（如 "# 公司 IT 常见问题"）
            if (chunk.isBlank() || chunk.split("\n").length < 2) {
                continue;
            }
            chunks.add(chunk);
        }
        return chunks.isEmpty() ? List.of(normalized) : chunks;
    }

    private Path resolveKnowledgeFile() {
        String configured = properties.getKnowledgeBasePath();
        if (configured == null || configured.isBlank()) {
            configured = "docs/it-knowledge-base.md";
        }
        Path p = Path.of(configured.trim());
        if (p.isAbsolute()) {
            return p;
        }
        return Path.of(System.getProperty("user.dir")).resolve(p).normalize();
    }
}

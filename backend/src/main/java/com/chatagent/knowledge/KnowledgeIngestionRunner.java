package com.chatagent.knowledge;

import com.chatagent.config.DashScopeProperties;
import com.chatagent.llm.DashScopeEmbeddingClient;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.io.InputStream;
import java.util.Arrays;
import java.nio.charset.StandardCharsets;
import java.util.List;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.core.io.Resource;
import org.springframework.core.env.Environment;
import org.springframework.core.io.support.PathMatchingResourcePatternResolver;
import org.springframework.dao.EmptyResultDataAccessException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

@Component
@RequiredArgsConstructor
@Slf4j
public class KnowledgeIngestionRunner {

    private final JdbcTemplate jdbcTemplate;
    private final DashScopeEmbeddingClient embeddingClient;
    private final ObjectMapper objectMapper;
    private final DashScopeProperties dashScopeProperties;
    private final Environment environment;

    private final KnowledgeChunker chunker = new KnowledgeChunker();

    @EventListener(ApplicationReadyEvent.class)
    public void onReady() throws Exception {
        Integer existing = jdbcTemplate.queryForObject(
                "SELECT COUNT(1) FROM knowledge_chunks", Integer.class);
        if (existing != null && existing > 0) {
            log.info("Knowledge ingestion skipped: knowledge_chunks already has {} rows", existing);
            return;
        }

        if (Arrays.stream(environment.getActiveProfiles()).anyMatch("test"::equals)) {
            log.info("Skip knowledge ingestion in test profile");
            return;
        }

        if (dashScopeProperties.getApiKey() == null || dashScopeProperties.getApiKey().isBlank()) {
            log.warn("Skip knowledge ingestion: DASHSCOPE_API_KEY is not configured");
            return;
        }

        PathMatchingResourcePatternResolver resolver = new PathMatchingResourcePatternResolver();
        Resource[] resources = resolver.getResources("classpath:knowledge/*.md");
        if (resources == null || resources.length == 0) {
            log.warn("No knowledge markdown found under classpath:knowledge/*.md");
            return;
        }

        log.info("Starting knowledge ingestion. files={}", resources.length);

        for (Resource r : resources) {
            String filename = r.getFilename();
            if (filename == null) {
                continue;
            }
            String docTitle = stripExtension(filename);
            String sourcePath = "knowledge/" + filename;

            String docText;
            try (InputStream is = r.getInputStream()) {
                docText = new String(is.readAllBytes(), StandardCharsets.UTF_8);
            }
            if (docText == null || docText.isBlank()) {
                log.warn("Skip empty knowledge doc: {}", sourcePath);
                continue;
            }

            long docId = requireOrCreateDoc(docTitle, sourcePath);
            List<KnowledgeChunker.ChunkDraft> chunks = chunker.chunkDoc(docTitle, docText);
            log.info("Ingest doc={} chunks={}", sourcePath, chunks.size());

            for (KnowledgeChunker.ChunkDraft c : chunks) {
                double[] embedding = embeddingClient.embed(c.chunkText());
                String embeddingJson = objectMapper.writeValueAsString(embedding);
                jdbcTemplate.update(
                        """
                        INSERT INTO knowledge_chunks(doc_id, doc_title, chunk_index, offset, chunk_text, embedding_json)
                        VALUES (?,?,?,?,?,?)
                        """,
                        docId,
                        docTitle,
                        c.chunkIndex(),
                        c.offset(),
                        c.chunkText(),
                        embeddingJson);
            }
        }

        log.info("Knowledge ingestion completed.");
    }

    private long requireOrCreateDoc(String docTitle, String sourcePath) {
        Long existingId =
                findDocId(sourcePath);
        if (existingId != null) {
            return existingId;
        }
        jdbcTemplate.update(
                "INSERT INTO knowledge_docs(doc_title, source_path) VALUES (?,?)", docTitle, sourcePath);
        Long docId = jdbcTemplate.queryForObject("SELECT last_insert_rowid()", Long.class);
        if (docId == null) {
            throw new IllegalStateException("Failed to obtain knowledge_docs last_insert_rowid()");
        }
        return docId;
    }

    private Long findDocId(String sourcePath) {
        try {
            return jdbcTemplate.queryForObject(
                    "SELECT id FROM knowledge_docs WHERE source_path = ?",
                    Long.class,
                    sourcePath);
        } catch (EmptyResultDataAccessException e) {
            return null;
        }
    }

    private static String stripExtension(String filename) {
        int idx = filename.lastIndexOf('.');
        if (idx <= 0) {
            return filename;
        }
        return filename.substring(0, idx);
    }
}


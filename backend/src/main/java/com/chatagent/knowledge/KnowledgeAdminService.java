package com.chatagent.knowledge;

import com.chatagent.common.ApiException;
import com.chatagent.llm.DashScopeEmbeddingClient;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Instant;
import java.util.List;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.dao.EmptyResultDataAccessException;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
@RequiredArgsConstructor
@Slf4j
public class KnowledgeAdminService {

    private final JdbcTemplate jdbcTemplate;
    private final DashScopeEmbeddingClient embeddingClient;
    private final ObjectMapper objectMapper;
    private final KnowledgeChunker chunker = new KnowledgeChunker();

    public record KnowledgeDocRow(
            long id,
            String docTitle,
            String sourcePath,
            Integer version,
            String docHash,
            Instant createdAt,
            Instant updatedAt,
            int chunkCount) {}

    public List<KnowledgeDocRow> listDocs() {
        return jdbcTemplate.query(
                """
                SELECT d.id,
                       d.doc_title,
                       d.source_path,
                       d.version,
                       d.doc_hash,
                       d.created_at,
                       d.updated_at,
                       (SELECT COUNT(1) FROM knowledge_chunks c WHERE c.doc_id = d.id) AS chunk_count
                  FROM knowledge_docs d
                 ORDER BY d.updated_at DESC, d.id DESC
                """,
                (rs, i) ->
                        new KnowledgeDocRow(
                                rs.getLong("id"),
                                rs.getString("doc_title"),
                                rs.getString("source_path"),
                                (Integer) rs.getObject("version"),
                                rs.getString("doc_hash"),
                                rs.getTimestamp("created_at") != null
                                        ? rs.getTimestamp("created_at").toInstant()
                                        : null,
                                rs.getTimestamp("updated_at") != null
                                        ? rs.getTimestamp("updated_at").toInstant()
                                        : null,
                                rs.getInt("chunk_count")));
    }

    @Transactional
    public long createDoc(String title, String sourcePath, String text) {
        String docTitle = title == null || title.isBlank() ? "untitled" : title.trim();
        if (text == null || text.isBlank()) {
            throw new ApiException(HttpStatus.BAD_REQUEST, "Empty document");
        }
        String hash = sha256Hex(text);
        jdbcTemplate.update(
                "INSERT INTO knowledge_docs(doc_title, source_path, doc_text, doc_hash, version, updated_at) VALUES (?,?,?,?,1,CURRENT_TIMESTAMP)",
                docTitle,
                sourcePath,
                text,
                hash);
        Long docId = jdbcTemplate.queryForObject("SELECT last_insert_rowid()", Long.class);
        if (docId == null) {
            throw new IllegalStateException("Failed to obtain knowledge_docs last_insert_rowid()");
        }
        upsertChunks(docId, docTitle, text);
        return docId;
    }

    @Transactional
    public void updateDoc(long docId, String newTitle, String newText) {
        DocSnapshot cur = requireSnapshot(docId);
        String title = newTitle == null || newTitle.isBlank() ? cur.docTitle : newTitle.trim();
        if (newText == null || newText.isBlank()) {
            throw new ApiException(HttpStatus.BAD_REQUEST, "Empty document");
        }
        String newHash = sha256Hex(newText);

        // Save current as a version record
        if (cur.docText != null && !cur.docText.isBlank()) {
            jdbcTemplate.update(
                    "INSERT INTO knowledge_doc_versions(doc_id, version, doc_text, doc_hash) VALUES (?,?,?,?)",
                    docId,
                    cur.version,
                    cur.docText,
                    cur.docHash);
        }

        int nextVersion = cur.version + 1;
        jdbcTemplate.update(
                "UPDATE knowledge_docs SET doc_title=?, doc_text=?, doc_hash=?, version=?, updated_at=CURRENT_TIMESTAMP WHERE id=?",
                title,
                newText,
                newHash,
                nextVersion,
                docId);

        jdbcTemplate.update("DELETE FROM knowledge_chunks WHERE doc_id=?", docId);
        upsertChunks(docId, title, newText);
    }

    @Transactional
    public void reindex(long docId) {
        DocSnapshot cur = requireSnapshot(docId);
        if (cur.docText == null || cur.docText.isBlank()) {
            throw new ApiException(
                    HttpStatus.BAD_REQUEST,
                    "Document text is missing (likely classpath-ingested). Re-upload it to make it manageable.");
        }
        jdbcTemplate.update("DELETE FROM knowledge_chunks WHERE doc_id=?", docId);
        upsertChunks(docId, cur.docTitle, cur.docText);
        jdbcTemplate.update("UPDATE knowledge_docs SET updated_at=CURRENT_TIMESTAMP WHERE id=?", docId);
    }

    @Transactional
    public void rollback(long docId, int toVersion) {
        DocSnapshot cur = requireSnapshot(docId);
        VersionSnapshot target = requireVersion(docId, toVersion);

        // Save current into versions table for redo
        if (cur.docText != null && !cur.docText.isBlank()) {
            jdbcTemplate.update(
                    "INSERT INTO knowledge_doc_versions(doc_id, version, doc_text, doc_hash) VALUES (?,?,?,?)",
                    docId,
                    cur.version,
                    cur.docText,
                    cur.docHash);
        }

        jdbcTemplate.update(
                "UPDATE knowledge_docs SET doc_text=?, doc_hash=?, version=?, updated_at=CURRENT_TIMESTAMP WHERE id=?",
                target.docText,
                target.docHash,
                toVersion,
                docId);

        jdbcTemplate.update("DELETE FROM knowledge_chunks WHERE doc_id=?", docId);
        upsertChunks(docId, cur.docTitle, target.docText);
    }

    private void upsertChunks(long docId, String docTitle, String docText) {
        List<KnowledgeChunker.ChunkDraft> chunks = chunker.chunkDoc(docTitle, docText);
        for (KnowledgeChunker.ChunkDraft c : chunks) {
            double[] embedding = embeddingClient.embed(c.chunkText());
            String embeddingJson;
            try {
                embeddingJson = objectMapper.writeValueAsString(embedding);
            } catch (Exception e) {
                log.warn("event=knowledge_embedding_serialize_failed docId={} err={}", docId, e.toString());
                continue;
            }
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

    private record DocSnapshot(long id, String docTitle, String docText, String docHash, int version) {}

    private DocSnapshot requireSnapshot(long docId) {
        try {
            return jdbcTemplate.queryForObject(
                    "SELECT id, doc_title, doc_text, doc_hash, version FROM knowledge_docs WHERE id=?",
                    (rs, i) ->
                            new DocSnapshot(
                                    rs.getLong("id"),
                                    rs.getString("doc_title"),
                                    rs.getString("doc_text"),
                                    rs.getString("doc_hash"),
                                    rs.getInt("version")),
                    docId);
        } catch (EmptyResultDataAccessException e) {
            throw new ApiException(HttpStatus.NOT_FOUND, "Knowledge doc not found");
        }
    }

    private record VersionSnapshot(int version, String docText, String docHash) {}

    private VersionSnapshot requireVersion(long docId, int version) {
        try {
            return jdbcTemplate.queryForObject(
                    "SELECT version, doc_text, doc_hash FROM knowledge_doc_versions WHERE doc_id=? AND version=?",
                    (rs, i) -> new VersionSnapshot(rs.getInt("version"), rs.getString("doc_text"), rs.getString("doc_hash")),
                    docId,
                    version);
        } catch (EmptyResultDataAccessException e) {
            throw new ApiException(HttpStatus.NOT_FOUND, "Target version not found");
        }
    }

    private static String sha256Hex(String text) {
        try {
            MessageDigest md = MessageDigest.getInstance("SHA-256");
            byte[] digest = md.digest(text.getBytes(StandardCharsets.UTF_8));
            StringBuilder sb = new StringBuilder(digest.length * 2);
            for (byte b : digest) {
                sb.append(String.format("%02x", b));
            }
            return sb.toString();
        } catch (Exception e) {
            return null;
        }
    }
}


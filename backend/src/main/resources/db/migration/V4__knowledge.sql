-- Local markdown knowledge base (Phase 2 minimal RAG)

CREATE TABLE IF NOT EXISTS knowledge_docs (
    id INTEGER PRIMARY KEY AUTOINCREMENT,
    doc_title VARCHAR(255) NOT NULL,
    source_path VARCHAR(512) NOT NULL UNIQUE,
    created_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP
);

CREATE TABLE IF NOT EXISTS knowledge_chunks (
    id INTEGER PRIMARY KEY AUTOINCREMENT,
    doc_id INTEGER NOT NULL,
    doc_title VARCHAR(255) NOT NULL,
    chunk_index INTEGER NOT NULL,
    offset INTEGER NOT NULL,
    chunk_text TEXT NOT NULL,
    embedding_json TEXT NOT NULL,
    created_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
    CONSTRAINT fk_knowledge_chunks_doc FOREIGN KEY (doc_id) REFERENCES knowledge_docs (id)
);

CREATE INDEX IF NOT EXISTS idx_knowledge_chunks_doc ON knowledge_chunks (doc_id);
CREATE INDEX IF NOT EXISTS idx_knowledge_chunks_title ON knowledge_chunks (doc_title);


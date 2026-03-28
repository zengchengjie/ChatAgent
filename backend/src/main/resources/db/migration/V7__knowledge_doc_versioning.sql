ALTER TABLE knowledge_docs ADD COLUMN doc_text TEXT;
ALTER TABLE knowledge_docs ADD COLUMN doc_hash VARCHAR(64);
ALTER TABLE knowledge_docs ADD COLUMN version INTEGER NOT NULL DEFAULT 1;
ALTER TABLE knowledge_docs ADD COLUMN updated_at TIMESTAMP;

UPDATE knowledge_docs SET updated_at = COALESCE(updated_at, created_at);

CREATE TABLE IF NOT EXISTS knowledge_doc_versions (
    id INTEGER PRIMARY KEY AUTOINCREMENT,
    doc_id INTEGER NOT NULL,
    version INTEGER NOT NULL,
    doc_text TEXT NOT NULL,
    doc_hash VARCHAR(64),
    created_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
    CONSTRAINT fk_kdv_doc FOREIGN KEY (doc_id) REFERENCES knowledge_docs (id)
);

CREATE INDEX IF NOT EXISTS idx_kdv_doc_id ON knowledge_doc_versions(doc_id);


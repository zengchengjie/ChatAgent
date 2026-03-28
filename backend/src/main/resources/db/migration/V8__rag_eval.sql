CREATE TABLE IF NOT EXISTS rag_eval_runs (
    id VARCHAR(36) PRIMARY KEY,
    k INTEGER NOT NULL,
    min_score DOUBLE NOT NULL,
    case_count INTEGER NOT NULL,
    hits INTEGER NOT NULL,
    recall_at_k DOUBLE NOT NULL,
    mrr DOUBLE NOT NULL,
    avg_latency_ms DOUBLE NOT NULL,
    created_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP
);

CREATE TABLE IF NOT EXISTS rag_eval_case_results (
    id INTEGER PRIMARY KEY AUTOINCREMENT,
    run_id VARCHAR(36) NOT NULL,
    query TEXT NOT NULL,
    expected_doc_title VARCHAR(255),
    found_rank INTEGER,
    found_score DOUBLE,
    latency_ms INTEGER NOT NULL,
    created_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP
);

CREATE INDEX IF NOT EXISTS idx_rag_eval_case_results_run_id ON rag_eval_case_results(run_id);


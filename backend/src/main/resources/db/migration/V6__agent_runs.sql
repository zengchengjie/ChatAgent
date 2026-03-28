CREATE TABLE IF NOT EXISTS agent_runs (
    id VARCHAR(36) PRIMARY KEY,
    trace_id VARCHAR(64) NOT NULL,
    user_id BIGINT NOT NULL,
    session_id VARCHAR(36) NOT NULL,
    engine VARCHAR(32) NOT NULL,
    model VARCHAR(128),
    prompt_tokens INTEGER,
    completion_tokens INTEGER,
    total_tokens INTEGER,
    tokens_estimated BOOLEAN NOT NULL DEFAULT 0,
    cost_usd DOUBLE,
    llm_calls INTEGER NOT NULL DEFAULT 0,
    started_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
    ended_at TIMESTAMP,
    error_code VARCHAR(64)
);

CREATE INDEX IF NOT EXISTS idx_agent_runs_trace_id ON agent_runs(trace_id);
CREATE INDEX IF NOT EXISTS idx_agent_runs_user_session ON agent_runs(user_id, session_id);


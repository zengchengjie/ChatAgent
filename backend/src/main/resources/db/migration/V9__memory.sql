CREATE TABLE IF NOT EXISTS chat_summaries (
    session_id VARCHAR(36) PRIMARY KEY,
    summary TEXT NOT NULL,
    last_message_id BIGINT NOT NULL,
    updated_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP
);

CREATE TABLE IF NOT EXISTS user_memories (
    id INTEGER PRIMARY KEY AUTOINCREMENT,
    user_id BIGINT NOT NULL,
    mem_key VARCHAR(128) NOT NULL,
    mem_value TEXT NOT NULL,
    updated_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
    UNIQUE(user_id, mem_key)
);

CREATE INDEX IF NOT EXISTS idx_user_memories_user_id ON user_memories(user_id);


-- Add per-session model selection for DashScope.
ALTER TABLE chat_sessions ADD COLUMN model VARCHAR(128);


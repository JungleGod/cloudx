-- ============================================
-- Agent 对话持久化：给 conversation 表加 agent_id
-- 说明: NULL=普通对话, 非NULL=Agent 对话
-- ============================================

ALTER TABLE conversation ADD COLUMN agent_id BIGINT DEFAULT NULL COMMENT 'Agent ID（Agent对话时关联）' AFTER model;

CREATE INDEX idx_agent_id ON conversation(agent_id);

-- ============================================
-- Agent 对话历史：消息表加 metadata 字段存 toolSteps
-- 解决：Agent 回复中的表格实时显示正常，刷新后加载历史就乱
-- 说明：实时渲染时工具结果单独展示为 HTML 表格，历史加载需要还原
-- ============================================

ALTER TABLE conversation_message ADD COLUMN metadata TEXT DEFAULT NULL COMMENT 'JSON metadata (tool steps, etc.)' AFTER content;

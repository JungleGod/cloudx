-- ============================================
-- 第三方 Agent 接入（L2）：external 执行类型
-- 创建日期: 2026-09-20
-- 说明: agent_definition 增加 external 执行类型，
--       AgentDispatchHandler 按类型分流：internal→本地 AgentLoop，external→HTTP 任务委托
-- 契约: OpenAI 兼容 POST {endpoint_url}/chat/completions（事实标准，Dify/Coze/自建网关均支持）
-- 执行: docker exec -i cloudx-mysql mysql -uroot -p<密码> --default-character-set=utf8mb4 cloudx < migration_20260920_external_agent.sql
-- ============================================

SET NAMES utf8mb4;

ALTER TABLE agent_definition
    ADD COLUMN execution_type VARCHAR(20) NOT NULL DEFAULT 'internal'
        COMMENT '执行类型: internal=平台内置(本地AgentLoop) / external=第三方(OpenAI兼容HTTP委托)' AFTER max_iterations,
    ADD COLUMN endpoint_url VARCHAR(500) DEFAULT NULL
        COMMENT '第三方Agent地址（execution_type=external 时必填，如 https://xxx/api/v1/chat/completions）' AFTER execution_type,
    ADD COLUMN endpoint_key VARCHAR(200) DEFAULT NULL
        COMMENT '第三方鉴权Key（AES加密落库，密钥同 SECRET_ENCRYPT_KEY）' AFTER endpoint_url,
    ADD COLUMN dispatch_timeout_ms INT DEFAULT 60000
        COMMENT '第三方Agent调用超时（毫秒），外部Agent内部有自己的循环，默认60s' AFTER endpoint_key,
    ADD INDEX idx_exec_type (execution_type);

-- 示例：注册一个第三方 Agent（数据用 UNHEX 写入防乱码，Key 由管理端 AES 加密后替换）
-- INSERT INTO agent_definition
--   (name, description, system_prompt, model, temperature, max_iterations,
--    execution_type, endpoint_url, endpoint_key, dispatch_timeout_ms, status, created_by)
-- VALUES (
--   UNHEX('E695B0E68DAEE58886E69E904167656E74'),  -- '数据分析Agent' 的 UTF-8 字节
--   '第三方数据分析Agent（示例）',
--   'external',  -- external Agent 的 system_prompt 不生效（提示词在对方平台），仅作占位说明
--   NULL, 0.7, 1,
--   'external', 'https://dify.example.com/v1/chat/completions',
--   NULL,       -- ← 用管理端或 SQL+AES 加密后填入
--   60000, 1, 1);
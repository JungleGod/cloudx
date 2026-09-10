-- ============================================
-- 模型配置表（模型定义 + 上游 Key + 定价统一落库）
-- 日期：2026-09-10
-- 说明：
--   模型定义（base_url / model_name / 标签 / 优先级 / fallback / 温度等）原先硬编码在
--   ai-agent 的 application.yml 的 cloudx.models，模型费率在 biz-service 的 cloudx.pricing。
--   本次统一收进 model_config 表，由 biz-service 拥有，前端 admin「模型管理」页做 CRUD，
--   ai-agent 通过 /api/internal/models 拉取并热刷新。
--
--   api_key 使用 AES 加密存储（AesUtil，密钥来自环境变量 SECRET_ENCRYPT_KEY）。
--   input_price / output_price 单位：元/千 token（与旧 cloudx.pricing 一致）。
-- ============================================

CREATE TABLE IF NOT EXISTS model_config (
    id BIGINT AUTO_INCREMENT PRIMARY KEY,
    name VARCHAR(64) NOT NULL COMMENT '模型标识，唯一',
    provider VARCHAR(64) NOT NULL DEFAULT 'openai-compatible' COMMENT '提供商类型',
    base_url VARCHAR(255) NOT NULL COMMENT 'API 地址',
    model_name VARCHAR(128) NOT NULL COMMENT '上游模型名',
    api_key VARCHAR(512) DEFAULT NULL COMMENT '上游 API Key（AES 加密）',
    tags VARCHAR(255) DEFAULT NULL COMMENT '逗号分隔路由标签',
    priority INT NOT NULL DEFAULT 10 COMMENT '优先级，越小越优先',
    max_failures INT NOT NULL DEFAULT 3 COMMENT '连续失败几次后熔断',
    fallback VARCHAR(64) DEFAULT NULL COMMENT '熔断后回退的模型名',
    temperature DECIMAL(4,2) NOT NULL DEFAULT 0.70 COMMENT '温度',
    max_tokens INT NOT NULL DEFAULT 2048 COMMENT '最大输出 token',
    timeout_seconds BIGINT NOT NULL DEFAULT 60 COMMENT '超时秒数',
    frequency_penalty DECIMAL(4,2) NOT NULL DEFAULT 0.30 COMMENT '频率惩罚 -2.0~2.0',
    presence_penalty DECIMAL(4,2) NOT NULL DEFAULT 0.30 COMMENT '存在惩罚 -2.0~2.0',
    input_price DECIMAL(10,6) NOT NULL DEFAULT 0.001000 COMMENT '输入单价（元/千token）',
    output_price DECIMAL(10,6) NOT NULL DEFAULT 0.002000 COMMENT '输出单价（元/千token）',
    status TINYINT NOT NULL DEFAULT 1 COMMENT '1启用 0停用',
    created_at DATETIME DEFAULT CURRENT_TIMESTAMP,
    updated_at DATETIME DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
    deleted TINYINT NOT NULL DEFAULT 0,
    UNIQUE KEY uk_name (name)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='模型配置';

-- ============================================
-- 种子数据：现有 5 个模型的元数据，api_key 留空
-- （由管理员在前端「模型管理」页填写真实 Key，AES 加密后落库）
-- 价格沿用旧 cloudx.pricing 的 deepseek [0.001,0.002] / qwen [0.003,0.006]，保持计费不变。
-- ============================================
INSERT INTO model_config
    (name, provider, base_url, model_name, api_key, tags, priority, max_failures, fallback,
     temperature, max_tokens, timeout_seconds, frequency_penalty, presence_penalty,
     input_price, output_price, status)
VALUES
    ('deepseek-v4-flash', 'openai-compatible', 'https://api.deepseek.com', 'deepseek-v4-flash', NULL,
     'fast,coding,general,chinese', 1, 3, 'deepseek-chat', 0.70, 2048, 60, 0.30, 0.30, 0.001000, 0.002000, 1),
    ('deepseek-chat', 'openai-compatible', 'https://api.deepseek.com', 'deepseek-chat', NULL,
     'coding,general,chinese,reasoning', 2, 3, 'qwen-turbo', 0.70, 2048, 60, 0.30, 0.30, 0.001000, 0.002000, 1),
    ('qwen-turbo', 'openai-compatible', 'https://dashscope.aliyuncs.com/compatible-mode/v1', 'qwen-turbo', NULL,
     'fast,multimodel,general,chinese', 3, 3, 'deepseek-chat', 0.70, 2048, 60, 0.30, 0.30, 0.003000, 0.006000, 1),
    ('deepseek-v4-pro', 'openai-compatible', 'https://api.deepseek.com', 'deepseek-v4-pro', NULL,
     'coding,reasoning,complex,general,chinese', 4, 3, 'deepseek-chat', 0.70, 4096, 120, 0.30, 0.30, 0.001000, 0.002000, 1),
    ('qwen-3.7-max', 'openai-compatible', 'https://dashscope.aliyuncs.com/compatible-mode/v1', 'qwen-3.7-max', NULL,
     'reasoning,complex,multimodel,general,chinese', 5, 3, 'deepseek-v4-pro', 0.70, 4096, 120, 0.30, 0.30, 0.003000, 0.006000, 1)
ON DUPLICATE KEY UPDATE name = VALUES(name);

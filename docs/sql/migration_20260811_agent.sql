-- ============================================
-- cloudx Agent 系统数据库迁移
-- 创建日期: 2026-08-11
-- 说明: Agent 定义、工具定义、Agent-工具绑定、执行审计日志
-- ============================================

-- 1. Agent 定义表
CREATE TABLE IF NOT EXISTS agent_definition (
    id              BIGINT       NOT NULL AUTO_INCREMENT COMMENT 'Agent ID',
    name            VARCHAR(100) NOT NULL COMMENT 'Agent 名称（唯一）',
    description     VARCHAR(500) DEFAULT NULL COMMENT '描述',
    system_prompt   TEXT         NOT NULL COMMENT '系统指令',
    model           VARCHAR(50)  DEFAULT NULL COMMENT '绑定模型名（NULL=自动路由）',
    temperature     DOUBLE       DEFAULT 0.7 COMMENT '温度参数',
    max_tokens      INT          DEFAULT 2048 COMMENT '最大 Token 数',
    max_iterations  INT          DEFAULT 5 COMMENT '最大工具调用轮数',
    status          TINYINT      NOT NULL DEFAULT 1 COMMENT '1-启用 0-禁用',
    created_by      BIGINT       NOT NULL COMMENT '创建者用户 ID',
    created_at      DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at      DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
    deleted         TINYINT      NOT NULL DEFAULT 0 COMMENT '逻辑删除',
    PRIMARY KEY (id),
    UNIQUE KEY uk_name (name),
    INDEX idx_created_by (created_by),
    INDEX idx_status (status)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='Agent 定义表';

-- 2. 工具定义表
CREATE TABLE IF NOT EXISTS tool_definition (
    id               BIGINT       NOT NULL AUTO_INCREMENT COMMENT '工具 ID',
    name             VARCHAR(100) NOT NULL COMMENT '函数名（唯一，如 get_user_count）',
    description      VARCHAR(500) NOT NULL COMMENT '工具描述（给 LLM 看的）',
    category         VARCHAR(20)  NOT NULL COMMENT '工具类别: built-in / http / internal-api',
    parameters_schema TEXT        NOT NULL COMMENT 'JSON Schema 参数定义',
    -- HTTP 工具配置
    http_method      VARCHAR(10)  DEFAULT NULL COMMENT 'HTTP 方法: GET/POST/PUT/DELETE',
    http_url         VARCHAR(500) DEFAULT NULL COMMENT 'HTTP URL 模板（支持 {var} 占位）',
    http_headers     TEXT         DEFAULT NULL COMMENT 'JSON 格式静态请求头',
    -- 内部 API 工具配置
    internal_path    VARCHAR(255) DEFAULT NULL COMMENT 'biz-service 内部 API 路径',
    -- 内置工具配置
    builtin_handler  VARCHAR(100) DEFAULT NULL COMMENT '内置处理器 Spring Bean 名',
    -- 权限控制
    required_role    VARCHAR(20)  NOT NULL DEFAULT 'user' COMMENT '最低角色: public/user/admin',
    timeout_ms       INT          DEFAULT 10000 COMMENT '执行超时（毫秒）',
    retry_count      INT          DEFAULT 0 COMMENT '失败重试次数',
    status           TINYINT      NOT NULL DEFAULT 1 COMMENT '1-启用 0-禁用',
    created_at       DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at       DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
    deleted          TINYINT      NOT NULL DEFAULT 0,
    PRIMARY KEY (id),
    UNIQUE KEY uk_name (name),
    INDEX idx_category (category),
    INDEX idx_required_role (required_role)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='工具定义表';

-- 3. Agent-工具绑定表（多对多）
CREATE TABLE IF NOT EXISTS agent_tool_binding (
    id          BIGINT NOT NULL AUTO_INCREMENT,
    agent_id    BIGINT NOT NULL COMMENT 'FK agent_definition.id',
    tool_id     BIGINT NOT NULL COMMENT 'FK tool_definition.id',
    PRIMARY KEY (id),
    UNIQUE KEY uk_agent_tool (agent_id, tool_id),
    INDEX idx_agent_id (agent_id),
    INDEX idx_tool_id (tool_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='Agent-工具绑定表';

-- 4. Agent 执行审计日志表
CREATE TABLE IF NOT EXISTS agent_execution_log (
    id              BIGINT       NOT NULL AUTO_INCREMENT,
    agent_id        BIGINT       DEFAULT NULL COMMENT 'FK agent_definition.id',
    user_id         BIGINT       NOT NULL COMMENT '调用者用户 ID',
    api_key_id      BIGINT       DEFAULT NULL COMMENT 'API Key ID（API 调用时）',
    session_id      VARCHAR(64)  NOT NULL COMMENT '执行会话 UUID',
    iteration       INT          NOT NULL DEFAULT 0 COMMENT 'Agent 循环轮次',
    step_type       VARCHAR(20)  NOT NULL COMMENT '步骤类型: llm_call/tool_call/tool_result/final_answer',
    model           VARCHAR(50)  DEFAULT NULL COMMENT '使用的模型名',
    tool_name       VARCHAR(100) DEFAULT NULL COMMENT '工具名（tool_call/tool_result 时）',
    request_body    MEDIUMTEXT   DEFAULT NULL COMMENT 'LLM 请求体或工具输入参数',
    response_body   MEDIUMTEXT   DEFAULT NULL COMMENT 'LLM 响应或工具输出',
    tokens_input    INT          DEFAULT 0,
    tokens_output   INT          DEFAULT 0,
    latency_ms      INT          DEFAULT 0,
    status          VARCHAR(20)  NOT NULL DEFAULT 'success' COMMENT 'success / error',
    error_msg       VARCHAR(1000) DEFAULT NULL,
    created_at      DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP,
    PRIMARY KEY (id),
    INDEX idx_session (session_id),
    INDEX idx_agent_user (agent_id, user_id),
    INDEX idx_created_at (created_at)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='Agent 执行审计日志';

-- ===== 种子数据：内置工具 =====
INSERT INTO tool_definition (name, description, category, parameters_schema, builtin_handler, required_role) VALUES
('get_user_count',     '查询系统注册用户总数',                    'built-in', '{"type":"object","properties":{},"required":[]}',  'getUserCountHandler',    'user'),
('get_daily_stats',    '获取今日API调用统计（总调用量、活跃用户数、总费用）', 'built-in', '{"type":"object","properties":{},"required":[]}',  'getDailyStatsHandler',   'user'),
('get_api_key_count',  '获取系统中的API Key总数',                'built-in', '{"type":"object","properties":{},"required":[]}',  'getApiKeyCountHandler',  'admin'),
('get_model_status',   '查看当前哪些AI模型在线可用',              'built-in', '{"type":"object","properties":{},"required":[]}',  'getModelStatusHandler',  'public'),
('get_my_usage',       '获取当前登录用户的今日API调用量和使用情况', 'built-in', '{"type":"object","properties":{},"required":[]}',  'getMyUsageHandler',      'user');

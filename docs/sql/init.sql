-- cloudx AI 网关平台 - 初始化建表脚本
-- 该脚本在 MySQL 容器首次启动时自动执行

-- ========== 用户表 ==========
CREATE TABLE IF NOT EXISTS sys_user (
    id          BIGINT       NOT NULL AUTO_INCREMENT COMMENT '用户ID',
    username    VARCHAR(50)  NOT NULL COMMENT '用户名',
    password    VARCHAR(255) NOT NULL COMMENT '密码（BCrypt加密）',
    email       VARCHAR(100) DEFAULT NULL COMMENT '邮箱',
    phone       VARCHAR(20)  DEFAULT NULL COMMENT '手机号',
    status      TINYINT      NOT NULL DEFAULT 1 COMMENT '状态：1-正常 0-禁用',
    created_at  DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
    updated_at  DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '更新时间',
    deleted     TINYINT      NOT NULL DEFAULT 0 COMMENT '逻辑删除：1-已删除 0-未删除',
    PRIMARY KEY (id),
    UNIQUE KEY uk_username (username)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='用户表';

-- ========== API Key 表 ==========
CREATE TABLE IF NOT EXISTS api_key (
    id           BIGINT       NOT NULL AUTO_INCREMENT COMMENT '主键ID',
    user_id      BIGINT       NOT NULL COMMENT '所属用户ID',
    access_key   VARCHAR(64)  NOT NULL COMMENT '访问密钥（公开）',
    secret_key   VARCHAR(128) NOT NULL COMMENT '秘密密钥（加密存储）',
    name         VARCHAR(100) DEFAULT NULL COMMENT 'Key 备注名称',
    status       TINYINT      NOT NULL DEFAULT 1 COMMENT '状态：1-启用 0-禁用',
    quota_daily  INT          DEFAULT 1000 COMMENT '每日调用配额',
    quota_total  INT          DEFAULT 10000 COMMENT '总调用配额',
    expired_at   DATETIME     DEFAULT NULL COMMENT '过期时间',
    created_at   DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
    updated_at   DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '更新时间',
    deleted      TINYINT      NOT NULL DEFAULT 0 COMMENT '逻辑删除',
    PRIMARY KEY (id),
    UNIQUE KEY uk_access_key (access_key),
    KEY idx_user_id (user_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='API Key 表';

-- ========== 接口定义表 ==========
CREATE TABLE IF NOT EXISTS api_interface (
    id            BIGINT       NOT NULL AUTO_INCREMENT COMMENT '主键ID',
    name          VARCHAR(100) NOT NULL COMMENT '接口名称',
    path          VARCHAR(255) NOT NULL COMMENT '请求路径',
    method        VARCHAR(10)  NOT NULL COMMENT '请求方法：GET/POST/PUT/DELETE',
    description   VARCHAR(500) DEFAULT NULL COMMENT '接口描述',
    model         VARCHAR(50)  DEFAULT NULL COMMENT '关联的AI模型（如 deepseek、qwen）',
    status        TINYINT      NOT NULL DEFAULT 1 COMMENT '状态：1-启用 0-禁用 2-维护中',
    created_at    DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
    updated_at    DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '更新时间',
    deleted       TINYINT      NOT NULL DEFAULT 0 COMMENT '逻辑删除',
    PRIMARY KEY (id),
    UNIQUE KEY uk_path_method (path, method)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='接口定义表';

-- ========== 调用日志表 ==========
CREATE TABLE IF NOT EXISTS call_log (
    id              BIGINT       NOT NULL AUTO_INCREMENT COMMENT '主键ID',
    user_id         BIGINT       NOT NULL COMMENT '调用用户ID',
    api_key_id      BIGINT       NOT NULL COMMENT '使用的API Key ID',
    interface_id    BIGINT       DEFAULT NULL COMMENT '调用的接口ID',
    model           VARCHAR(50)  DEFAULT NULL COMMENT '使用的AI模型',
    request_body    TEXT         DEFAULT NULL COMMENT '请求内容',
    response_body   MEDIUMTEXT   DEFAULT NULL COMMENT '响应内容',
    tokens_input    INT          DEFAULT 0 COMMENT '输入 token 数',
    tokens_output   INT          DEFAULT 0 COMMENT '输出 token 数',
    tokens_total    INT          DEFAULT 0 COMMENT '总 token 数',
    cost            DECIMAL(10,6) DEFAULT 0.000000 COMMENT '本次调用费用（元）',
    latency_ms      INT          DEFAULT 0 COMMENT '响应耗时（毫秒）',
    status          VARCHAR(20)  NOT NULL DEFAULT 'success' COMMENT '调用结果：success/fail/error',
    error_msg       VARCHAR(500) DEFAULT NULL COMMENT '错误信息',
    created_at      DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '调用时间',
    PRIMARY KEY (id),
    KEY idx_user_id (user_id),
    KEY idx_api_key_id (api_key_id),
    KEY idx_created_at (created_at),
    KEY idx_status (status)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='调用日志表';

-- ========== 初始数据 ==========
-- 默认管理员账号：admin / admin123（BCrypt 加密）
INSERT INTO sys_user (username, password, email) VALUES
('admin', '$2a$10$bxOuOs5bNT2BpvlAMF2TWONTKXiPUv9PPLuvRC0MNPfUlXIMw8wv.', 'admin@cloudx.com');

-- 多会话功能迁移
-- 2026-08-07

-- ========== 会话表 ==========
CREATE TABLE IF NOT EXISTS conversation (
    id          BIGINT       NOT NULL AUTO_INCREMENT COMMENT '会话ID',
    user_id     BIGINT       NOT NULL COMMENT '所属用户',
    title       VARCHAR(100) NOT NULL DEFAULT '新对话' COMMENT '会话标题',
    model       VARCHAR(50)  DEFAULT NULL COMMENT '使用的模型',
    created_at  DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
    updated_at  DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '更新时间',
    deleted     TINYINT      NOT NULL DEFAULT 0 COMMENT '逻辑删除',
    PRIMARY KEY (id),
    INDEX idx_user_id (user_id),
    INDEX idx_updated_at (updated_at)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='会话表';

-- ========== 会话消息表 ==========
CREATE TABLE IF NOT EXISTS conversation_message (
    id              BIGINT        NOT NULL AUTO_INCREMENT COMMENT '消息ID',
    conversation_id BIGINT        NOT NULL COMMENT '所属会话ID',
    role            VARCHAR(20)   NOT NULL COMMENT '角色：user / assistant',
    content         TEXT          NOT NULL COMMENT '消息内容',
    model           VARCHAR(50)   DEFAULT NULL COMMENT '回复的模型名（仅assistant消息）',
    tokens          INT           DEFAULT NULL COMMENT 'token消耗估算',
    created_at      DATETIME      NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
    PRIMARY KEY (id),
    INDEX idx_conversation_id (conversation_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='会话消息表';

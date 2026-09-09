-- 用户角色权限迁移
-- 2026-08-07

ALTER TABLE sys_user ADD COLUMN IF NOT EXISTS role VARCHAR(20) NOT NULL DEFAULT 'user' COMMENT '角色：admin-管理员 user-普通用户' AFTER status;

-- 将已有 admin 用户设为管理员
UPDATE sys_user SET role = 'admin' WHERE username = 'admin';
-- ============================================
-- 用户月度基础额度（金额，元）
-- 日期：2026-09-10
-- 说明：
--   每个用户每月有基础免费额度，默认 200 元，每月 1 号自动刷新（Redis 计数按月分片）。
--   monthly_quota 为 NULL 表示不限（admin 一律豁免，走角色判断，不依赖本字段）。
-- ============================================

-- 注意：MySQL 8.0 不支持 ADD COLUMN IF NOT EXISTS（那是 MariaDB 语法），
-- 因此这里用标准写法。本脚本只执行一次；若重复执行会报 Duplicate column（可忽略）。
ALTER TABLE sys_user
    ADD COLUMN monthly_quota DECIMAL(10, 2) NOT NULL DEFAULT 200.00
    COMMENT '每月基础额度（元），NULL 表示不限' AFTER role;

-- 历史用户默认 200，无需额外更新；如需给某用户调高/设不限，用管理接口
-- PUT /api/admin/users/{id}/quota
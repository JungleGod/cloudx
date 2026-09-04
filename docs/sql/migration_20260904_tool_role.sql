-- ============================================
-- 修复内置工具角色越权：全局统计工具应仅 admin 可用
-- 日期：2026-09-04
-- 说明：
--   get_user_count / get_daily_stats 返回全平台数据（用户总数、活跃用户数、总费用），
--   但 required_role 误设为 'user'，普通用户通过 Agent 调用即可看到全平台统计。
--   统一改为 'admin'，与 get_api_key_count 一致；普通用户个人数据用 get_my_usage。
-- ============================================

UPDATE tool_definition
SET required_role = 'admin'
WHERE name IN ('get_user_count', 'get_daily_stats');

-- 执行后需触发 ai-agent 热重载：POST /api/admin/tools/reload
-- （或重启 ai-agent，ToolRegistry 缓存才会刷新 required_role）
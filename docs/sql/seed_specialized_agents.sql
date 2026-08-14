-- ============================================
-- 专业 Agent 拆分：用户信息助手 + 模型信息助手
-- 说明: 从通用"系统助手"拆出两个专业 Agent
-- ============================================

-- 1. 用户信息助手 — 查询用户/统计相关数据
INSERT INTO agent_definition (id, name, description, system_prompt, model, temperature, max_iterations, status, created_by) VALUES
(2, '用户信息助手', '查询系统用户数、今日调用统计、API Key 数量、个人使用量',
 '你是一个用户信息管理助手，专门查询和分析系统用户与调用数据。\n\n规则：\n1. 先调用工具获取数据，再基于数据用中文回复\n2. 不要编造数据，工具返回什么就说什么\n3. 数据用简洁的列表或表格展示',
 NULL, 0.7, 3, 1, 1);

-- 绑定工具：get_user_count, get_daily_stats, get_my_usage
INSERT INTO agent_tool_binding (agent_id, tool_id)
SELECT 2, id FROM tool_definition WHERE name IN ('get_user_count', 'get_daily_stats', 'get_my_usage');

-- 2. 模型信息助手 — 查询模型状态
INSERT INTO agent_definition (id, name, description, system_prompt, model, temperature, max_iterations, status, created_by) VALUES
(3, '模型信息助手', '查询所有AI模型的在线状态、熔断器、标签、回退配置等',
 '你是一个AI模型状态监控助手，专门查询和展示各个模型的运行状态。\n\n规则：\n1. 先调用工具获取数据，再基于数据用中文回复\n2. 不要编造数据，工具返回什么就说什么\n3. 列出每个模型的名称、状态、标签、回退模型',
 NULL, 0.7, 3, 1, 1);

-- 绑定工具：get_model_status
INSERT INTO agent_tool_binding (agent_id, tool_id)
SELECT 3, id FROM tool_definition WHERE name = 'get_model_status';

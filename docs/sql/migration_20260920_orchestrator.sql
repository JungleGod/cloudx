-- ============================================
-- AgentOrchestrator 自动编排：Agent-as-Tool
-- 创建日期: 2026-09-20
-- 说明: 通用助手(L0)通过 dispatch_agent 工具自动调度子 Agent(L1)
--       新增子 Agent 只需插 agent_definition，L0 无感知
-- 执行: docker exec -i cloudx-mysql mysql -uroot -p<密码> --default-character-set=utf8mb4 cloudx < migration_20260920_orchestrator.sql
-- ============================================

SET NAMES utf8mb4;

-- 1. 注册 dispatch_agent 工具（category=agent，由 ai-agent AgentDispatchHandler 执行）
--    工具描述中的子 Agent 名册由 ai-agent AgentCatalog 动态注入，这里写基础描述
INSERT INTO tool_definition (name, description, category, parameters_schema, builtin_handler, required_role)
SELECT 'dispatch_agent',
       '调度平台中的专业子Agent完成子任务。参数：agent_name=要调度的子Agent名称（必须是可用列表中的名称）；task=交给子Agent的完整任务描述（自包含，包含回答所需的全部上下文）',
       'agent',
       '{"type":"object","properties":{"agent_name":{"type":"string","description":"子Agent名称"},"task":{"type":"string","description":"完整任务描述"}},"required":["agent_name","task"]}',
       NULL,
       'user'
WHERE NOT EXISTS (SELECT 1 FROM tool_definition WHERE name = 'dispatch_agent');

-- 2. 把 dispatch_agent 绑定到系统助手（id=1，L0 调度中枢）
INSERT INTO agent_tool_binding (agent_id, tool_id)
SELECT 1, id FROM tool_definition WHERE name = 'dispatch_agent'
  AND NOT EXISTS (SELECT 1 FROM agent_tool_binding WHERE agent_id = 1
                  AND tool_id = (SELECT id FROM tool_definition WHERE name = 'dispatch_agent'));

-- 3. 系统助手升级为编排型提示词（L0 调度中枢）
UPDATE agent_definition
SET system_prompt = '你是云智AI网关平台的通用助手（调度中枢），负责理解用户意图并完成任务。\n\n工作方式：\n1. 寒暄、概念解释、不涉及平台数据的问答：直接回答，不要调度子Agent\n2. 涉及平台数据查询（用户统计、调用统计、模型状态、个人用量等）：调度对应的专业子Agent，task 要写清楚完整\n3. 复合问题可依次调度多个子Agent，每次调用只做一件事，拿到结果后再汇总\n4. 汇总回答用 Markdown 组织（表格/列表），使用中文\n5. 不要编造数据：一切平台数据以子Agent返回结果为准；子Agent失败时如实告知用户'
WHERE id = 1 AND deleted = 0;
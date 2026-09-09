DELETE FROM agent_tool_binding WHERE agent_id=1;
DELETE FROM agent_definition WHERE id=1;

INSERT INTO agent_definition (id, name, description, system_prompt, model, temperature, max_iterations, status, created_by) VALUES
(1, '系统助手', '默认系统助手，可查询数据', '你是一个系统管理助手，可以查询系统数据来回答用户问题。先调用工具获取数据，再基于数据用中文回复。', NULL, 0.7, 5, 1, 1);

INSERT INTO agent_tool_binding (agent_id, tool_id) SELECT 1, id FROM tool_definition;

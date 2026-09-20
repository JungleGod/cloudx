package com.cloudx.biz.service;

import com.baomidou.mybatisplus.extension.service.IService;
import com.cloudx.biz.entity.AgentDefinition;
import com.cloudx.biz.entity.ToolDefinition;

import java.util.List;

public interface AgentDefinitionService extends IService<AgentDefinition> {

    /** 获取用户的 Agent 列表 */
    List<AgentDefinition> listByUser(Long userId);

    /** 创建 Agent */
    AgentDefinition create(AgentDefinition agent);

    /** 更新 Agent */
    void updateAgent(Long id, AgentDefinition agent);

    /** 获取 Agent 绑定的工具列表 */
    List<ToolDefinition> getBoundTools(Long agentId);

    /** 绑定工具到 Agent */
    void bindTools(Long agentId, List<Long> toolIds);

    /** 解绑工具 */
    void unbindTool(Long agentId, Long toolId);

    /** 解密第三方 Agent 的 endpointKey（仅内部接口下发给 ai-agent） */
    String decryptEndpointKey(String cipher);
}

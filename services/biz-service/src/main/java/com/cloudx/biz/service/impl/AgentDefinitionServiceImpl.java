package com.cloudx.biz.service.impl;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import com.cloudx.biz.entity.AgentDefinition;
import com.cloudx.biz.entity.AgentToolBinding;
import com.cloudx.biz.entity.ToolDefinition;
import com.cloudx.biz.mapper.AgentDefinitionMapper;
import com.cloudx.biz.mapper.AgentToolBindingMapper;
import com.cloudx.biz.mapper.ToolDefinitionMapper;
import com.cloudx.biz.service.AgentDefinitionService;
import com.cloudx.common.exception.BizException;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.stream.Collectors;

@Slf4j
@Service
@RequiredArgsConstructor
public class AgentDefinitionServiceImpl extends ServiceImpl<AgentDefinitionMapper, AgentDefinition>
        implements AgentDefinitionService {

    private final AgentToolBindingMapper bindingMapper;
    private final ToolDefinitionMapper toolMapper;

    @Override
    public List<AgentDefinition> listByUser(Long userId) {
        return list(new LambdaQueryWrapper<AgentDefinition>()
                .eq(AgentDefinition::getCreatedBy, userId)
                .orderByDesc(AgentDefinition::getUpdatedAt));
    }

    @Override
    @Transactional
    public AgentDefinition create(AgentDefinition agent) {
        if (agent.getTemperature() == null) agent.setTemperature(0.7);
        if (agent.getMaxTokens() == null) agent.setMaxTokens(2048);
        if (agent.getMaxIterations() == null) agent.setMaxIterations(5);
        if (agent.getStatus() == null) agent.setStatus(1);
        save(agent);
        log.info("Agent created: id={}, name={}", agent.getId(), agent.getName());
        return agent;
    }

    @Override
    @Transactional
    public void updateAgent(Long id, AgentDefinition agent) {
        AgentDefinition existing = getById(id);
        if (existing == null) {
            throw new BizException("Agent 不存在");
        }
        agent.setId(id);
        updateById(agent);
    }

    @Override
    public List<ToolDefinition> getBoundTools(Long agentId) {
        List<Long> toolIds = bindingMapper.selectList(
                new LambdaQueryWrapper<AgentToolBinding>()
                        .eq(AgentToolBinding::getAgentId, agentId))
                .stream().map(AgentToolBinding::getToolId)
                .collect(Collectors.toList());
        if (toolIds.isEmpty()) return List.of();
        return toolMapper.selectBatchIds(toolIds).stream()
                .filter(t -> t.getStatus() == 1)
                .collect(Collectors.toList());
    }

    @Override
    @Transactional
    public void bindTools(Long agentId, List<Long> toolIds) {
        // 先删旧绑定
        bindingMapper.delete(new LambdaQueryWrapper<AgentToolBinding>()
                .eq(AgentToolBinding::getAgentId, agentId));
        // 再插新绑定
        for (Long toolId : toolIds) {
            AgentToolBinding binding = new AgentToolBinding();
            binding.setAgentId(agentId);
            binding.setToolId(toolId);
            bindingMapper.insert(binding);
        }
        log.info("Agent {} bound {} tools: {}", agentId, toolIds.size(), toolIds);
    }

    @Override
    @Transactional
    public void unbindTool(Long agentId, Long toolId) {
        bindingMapper.delete(new LambdaQueryWrapper<AgentToolBinding>()
                .eq(AgentToolBinding::getAgentId, agentId)
                .eq(AgentToolBinding::getToolId, toolId));
    }
}

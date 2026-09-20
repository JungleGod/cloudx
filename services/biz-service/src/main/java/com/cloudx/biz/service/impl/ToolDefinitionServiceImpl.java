package com.cloudx.biz.service.impl;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import com.cloudx.biz.entity.AgentToolBinding;
import com.cloudx.biz.entity.ToolDefinition;
import com.cloudx.biz.mapper.AgentToolBindingMapper;
import com.cloudx.biz.mapper.ToolDefinitionMapper;
import com.cloudx.biz.service.ToolDefinitionService;
import com.cloudx.common.exception.BizException;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;

@Slf4j
@Service
@RequiredArgsConstructor
public class ToolDefinitionServiceImpl extends ServiceImpl<ToolDefinitionMapper, ToolDefinition>
        implements ToolDefinitionService {

    private final AgentToolBindingMapper bindingMapper;

    @Override
    public List<ToolDefinition> listEnabled() {
        return list(new LambdaQueryWrapper<ToolDefinition>()
                .eq(ToolDefinition::getStatus, 1));
    }

    @Override
    public List<ToolDefinition> listByCategory(String category) {
        return list(new LambdaQueryWrapper<ToolDefinition>()
                .eq(ToolDefinition::getCategory, category)
                .eq(ToolDefinition::getStatus, 1));
    }

    @Override
    public List<ToolDefinition> getByNames(List<String> names) {
        if (names == null || names.isEmpty()) {
            return listEnabled(); // 空列表 = 返回全部
        }
        return list(new LambdaQueryWrapper<ToolDefinition>()
                .in(ToolDefinition::getName, names)
                .eq(ToolDefinition::getStatus, 1));
    }

    @Override
    @Transactional
    public void createTool(ToolDefinition tool) {
        if (tool.getName() == null || tool.getName().isBlank()) {
            throw new BizException("工具名不能为空");
        }
        if ("built-in".equals(tool.getCategory())) {
            // built-in 的执行逻辑在 ai-agent 代码里，页面/API 注册只会产生死工具
            throw new BizException("built-in 工具由平台代码提供，不支持在线新建（请选择 http / internal-api 类别）");
        }
        if (count(new LambdaQueryWrapper<ToolDefinition>().eq(ToolDefinition::getName, tool.getName())) > 0) {
            throw new BizException("工具名已存在: " + tool.getName());
        }
        if (tool.getRequiredRole() == null || tool.getRequiredRole().isBlank()) tool.setRequiredRole("user");
        if (tool.getStatus() == null) tool.setStatus(1);
        if (tool.getTimeoutMs() == null) tool.setTimeoutMs(10000);
        if (tool.getRetryCount() == null) tool.setRetryCount(0);
        if (tool.getParametersSchema() == null || tool.getParametersSchema().isBlank()) {
            tool.setParametersSchema("{\"type\":\"object\",\"properties\":{},\"required\":[]}");
        }
        save(tool);
        log.info("Tool created: id={}, name={}, category={}", tool.getId(), tool.getName(), tool.getCategory());
    }

    @Override
    @Transactional
    public void updateTool(Long id, ToolDefinition tool) {
        ToolDefinition existing = getById(id);
        if (existing == null) {
            throw new BizException("工具不存在");
        }
        // name 唯一性校验（排除自身）
        if (tool.getName() != null && !tool.getName().isBlank() && !tool.getName().equals(existing.getName())) {
            long dup = count(new LambdaQueryWrapper<ToolDefinition>()
                    .eq(ToolDefinition::getName, tool.getName())
                    .ne(ToolDefinition::getId, id));
            if (dup > 0) {
                throw new BizException("工具名已存在: " + tool.getName());
            }
        }
        tool.setId(id);
        updateById(tool); // null 字段跳过，保留原值
        log.info("Tool updated: id={}, name={}", id, tool.getName());
    }

    @Override
    @Transactional
    public void deleteTool(Long id) {
        ToolDefinition existing = getById(id);
        if (existing == null) {
            throw new BizException("工具不存在");
        }
        // 级联解除所有 Agent 绑定，避免出现死引用
        int unbound = bindingMapper.delete(new LambdaQueryWrapper<AgentToolBinding>()
                .eq(AgentToolBinding::getToolId, id));
        removeById(id);
        log.info("Tool deleted: id={}, name={}, unbound {} bindings", id, existing.getName(), unbound);
    }
}

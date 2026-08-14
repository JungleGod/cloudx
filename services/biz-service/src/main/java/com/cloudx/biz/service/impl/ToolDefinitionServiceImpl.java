package com.cloudx.biz.service.impl;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import com.cloudx.biz.entity.ToolDefinition;
import com.cloudx.biz.mapper.ToolDefinitionMapper;
import com.cloudx.biz.service.ToolDefinitionService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.util.List;

@Slf4j
@Service
@RequiredArgsConstructor
public class ToolDefinitionServiceImpl extends ServiceImpl<ToolDefinitionMapper, ToolDefinition>
        implements ToolDefinitionService {

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
}

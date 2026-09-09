package com.cloudx.biz.service;

import com.baomidou.mybatisplus.extension.service.IService;
import com.cloudx.biz.entity.ToolDefinition;

import java.util.List;

public interface ToolDefinitionService extends IService<ToolDefinition> {

    /** 获取所有启用的工具 */
    List<ToolDefinition> listEnabled();

    /** 按类别获取工具 */
    List<ToolDefinition> listByCategory(String category);

    /** 批量按名称获取 */
    List<ToolDefinition> getByNames(List<String> names);
}

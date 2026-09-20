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

    /** 创建工具（built-in 类别禁止页面/API 新建，只能走代码+SQL） */
    void createTool(ToolDefinition tool);

    /** 更新工具（null 字段跳过，保留原值） */
    void updateTool(Long id, ToolDefinition tool);

    /** 删除工具（级联解除所有 Agent 绑定） */
    void deleteTool(Long id);
}

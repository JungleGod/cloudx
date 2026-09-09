package com.cloudx.biz.entity;

import com.baomidou.mybatisplus.annotation.*;
import lombok.Data;

import java.time.LocalDateTime;

@Data
@TableName("agent_definition")
public class AgentDefinition {

    @TableId(type = IdType.AUTO)
    private Long id;

    private String name;
    private String description;
    private String systemPrompt;
    private String model;
    private Double temperature;
    private Integer maxTokens;
    private Integer maxIterations;
    private Integer status;
    private Long createdBy;

    @TableField(fill = FieldFill.INSERT)
    private LocalDateTime createdAt;

    @TableField(fill = FieldFill.INSERT_UPDATE)
    private LocalDateTime updatedAt;

    @TableLogic
    private Integer deleted;
}

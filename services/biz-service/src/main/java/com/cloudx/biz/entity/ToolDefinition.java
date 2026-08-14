package com.cloudx.biz.entity;

import com.baomidou.mybatisplus.annotation.*;
import lombok.Data;

import java.time.LocalDateTime;

@Data
@TableName("tool_definition")
public class ToolDefinition {

    @TableId(type = IdType.AUTO)
    private Long id;

    private String name;
    private String description;
    private String category;
    private String parametersSchema;
    private String httpMethod;
    private String httpUrl;
    private String httpHeaders;
    private String internalPath;
    private String builtinHandler;
    private String requiredRole;
    private Integer timeoutMs;
    private Integer retryCount;
    private Integer status;

    @TableField(fill = FieldFill.INSERT)
    private LocalDateTime createdAt;

    @TableField(fill = FieldFill.INSERT_UPDATE)
    private LocalDateTime updatedAt;

    @TableLogic
    private Integer deleted;
}

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

    /** 执行类型: internal=平台内置(本地AgentLoop) / external=第三方(OpenAI兼容HTTP委托) */
    private String executionType;

    /** 第三方Agent地址（execution_type=external 时必填） */
    private String endpointUrl;

    /** 第三方鉴权Key（AES加密落库，接口返回时脱敏/不下发） */
    private String endpointKey;

    /** 第三方Agent调用超时（毫秒） */
    private Integer dispatchTimeoutMs;

    private Integer status;
    private Long createdBy;

    @TableField(fill = FieldFill.INSERT)
    private LocalDateTime createdAt;

    @TableField(fill = FieldFill.INSERT_UPDATE)
    private LocalDateTime updatedAt;

    @TableLogic
    private Integer deleted;
}

package com.cloudx.biz.entity;

import com.baomidou.mybatisplus.annotation.*;
import lombok.Data;

import java.time.LocalDateTime;

@Data
@TableName("agent_execution_log")
public class AgentExecutionLog {

    @TableId(type = IdType.AUTO)
    private Long id;

    private Long agentId;
    private Long userId;
    private Long apiKeyId;
    private String sessionId;
    private Integer iteration;
    private String stepType;
    private String model;
    private String toolName;
    private String requestBody;
    private String responseBody;
    private Integer tokensInput;
    private Integer tokensOutput;
    private Integer latencyMs;
    private String status;
    private String errorMsg;

    @TableField(fill = FieldFill.INSERT)
    private LocalDateTime createdAt;
}

package com.cloudx.biz.entity;

import com.baomidou.mybatisplus.annotation.*;
import lombok.Data;

import java.math.BigDecimal;
import java.time.LocalDateTime;

@Data
@TableName("call_log")
public class CallLog {

    @TableId(type = IdType.AUTO)
    private Long id;
    private Long userId;
    private Long apiKeyId;
    private Long interfaceId;
    private String model;
    /** 客户端请求的模型名（auto=自动路由），实际模型存 model 列 */
    private String requestedModel;
    private String requestBody;
    private String responseBody;
    private Integer tokensInput;
    private Integer tokensOutput;
    private Integer tokensTotal;
    private BigDecimal cost;
    private Integer latencyMs;
    private String status;
    private String errorMsg;

    @TableField(fill = FieldFill.INSERT)
    private LocalDateTime createdAt;
}

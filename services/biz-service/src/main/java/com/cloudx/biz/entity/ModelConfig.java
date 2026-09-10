package com.cloudx.biz.entity;

import com.baomidou.mybatisplus.annotation.*;
import lombok.Data;

import java.math.BigDecimal;
import java.time.LocalDateTime;

/**
 * 模型配置 — 模型定义 + 上游 Key（AES 加密）+ 定价，统一落库
 */
@Data
@TableName("model_config")
public class ModelConfig {

    @TableId(type = IdType.AUTO)
    private Long id;

    /** 模型标识，唯一 */
    private String name;

    /** 提供商类型：openai-compatible */
    private String provider;

    /** API 地址 */
    private String baseUrl;

    /** 上游模型名 */
    private String modelName;

    /** 上游 API Key（AES 加密存储） */
    private String apiKey;

    /** 逗号分隔路由标签 */
    private String tags;

    /** 优先级，越小越优先 */
    private Integer priority;

    /** 连续失败几次后熔断 */
    private Integer maxFailures;

    /** 熔断后回退的模型名 */
    private String fallback;

    /** 温度 */
    private Double temperature;

    /** 最大输出 token */
    private Integer maxTokens;

    /** 超时秒数 */
    private Long timeoutSeconds;

    /** 频率惩罚 -2.0~2.0 */
    private Double frequencyPenalty;

    /** 存在惩罚 -2.0~2.0 */
    private Double presencePenalty;

    /** 输入单价（元/千 token） */
    private BigDecimal inputPrice;

    /** 输出单价（元/千 token） */
    private BigDecimal outputPrice;

    /** 1启用 0停用 */
    private Integer status;

    @TableField(fill = FieldFill.INSERT)
    private LocalDateTime createdAt;

    @TableField(fill = FieldFill.INSERT_UPDATE)
    private LocalDateTime updatedAt;

    @TableLogic
    private Integer deleted;
}

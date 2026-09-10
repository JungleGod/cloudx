package com.cloudx.biz.dto;

import lombok.Builder;
import lombok.Data;

import java.math.BigDecimal;
import java.time.LocalDateTime;

/**
 * 模型配置脱敏返回体 — 不返回明文 apiKey
 */
@Data
@Builder
public class ModelConfigVO {

    private Long id;
    private String name;
    private String provider;
    private String baseUrl;
    private String modelName;

    /** 是否已配置上游 Key */
    private Boolean hasApiKey;

    /** 脱敏后的 Key 提示（如 sk-a...1234），未配置为空 */
    private String apiKeyMasked;

    /** 逗号分隔路由标签 */
    private String tags;
    private Integer priority;
    private Integer maxFailures;
    private String fallback;
    private Double temperature;
    private Integer maxTokens;
    private Long timeoutSeconds;
    private Double frequencyPenalty;
    private Double presencePenalty;
    private BigDecimal inputPrice;
    private BigDecimal outputPrice;
    private Integer status;

    private LocalDateTime createdAt;
    private LocalDateTime updatedAt;
}

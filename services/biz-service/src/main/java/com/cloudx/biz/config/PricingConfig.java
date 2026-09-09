package com.cloudx.biz.config;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.cloud.context.config.annotation.RefreshScope;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * 模型定价配置 — 从 Nacos biz-service.yaml 加载，支持热更新
 */
@Data
@Component
@RefreshScope
@ConfigurationProperties(prefix = "cloudx.pricing")
public class PricingConfig {

    /** 模型名 → [输入单价, 输出单价]，单位：元/千token */
    private Map<String, List<BigDecimal>> pricing = new HashMap<>();

    /**
     * 获取指定模型的定价，找不到则返回默认定价
     */
    public BigDecimal[] getOrDefault(String model) {
        List<BigDecimal> prices = pricing.get(model);
        if (prices != null && prices.size() >= 2) {
            return new BigDecimal[]{prices.get(0), prices.get(1)};
        }
        // 默认：deepseek 定价
        return new BigDecimal[]{new BigDecimal("0.001"), new BigDecimal("0.002")};
    }
}

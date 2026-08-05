package com.cloudx.aiagent.config;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.cloud.context.config.annotation.RefreshScope;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;

@Data
@Component
@RefreshScope
@ConfigurationProperties(prefix = "cloudx")
public class ModelConfig {

    private List<ModelInfo> models = new ArrayList<>();

    @Data
    public static class ModelInfo {
        /** 模型名称，唯一标识 */
        private String name;
        /** 提供商类型：openai-compatible */
        private String provider;
        /** API 地址 */
        private String baseUrl;
        /** 模型名 */
        private String modelName;
        /** API Key 列表（多Key负载均衡） */
        private List<String> keys = new ArrayList<>();
        /** 路由标签 */
        private List<String> tags = new ArrayList<>();
        /** 优先级，越小越优先 */
        private int priority = 10;
        /** 连续失败几次后熔断 */
        private int maxFailures = 3;
        /** 熔断后回退到哪个模型 */
        private String fallback;
        /** 温度 */
        private double temperature = 0.7;
        /** 最大token数 */
        private int maxTokens = 2048;
        /** 超时秒数 */
        private long timeoutSeconds = 60;

        public boolean hasKeys() {
            return keys != null && !keys.isEmpty() && keys.stream().anyMatch(k -> k != null && !k.isBlank());
        }
    }
}

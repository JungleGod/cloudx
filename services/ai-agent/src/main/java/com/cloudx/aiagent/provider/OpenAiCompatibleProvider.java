package com.cloudx.aiagent.provider;

import com.cloudx.aiagent.config.ModelConfig;
import com.cloudx.aiagent.failover.FailoverHandler;
import com.cloudx.aiagent.loadbalance.LoadBalancer;
import dev.langchain4j.model.openai.OpenAiChatModel;
import lombok.Getter;
import lombok.extern.slf4j.Slf4j;

import java.time.Duration;

/**
 * OpenAI 兼容格式的模型提供者
 * DeepSeek、通义千问、GLM、OpenAI 等都兼容此格式
 */
@Slf4j
public class OpenAiCompatibleProvider implements ModelProvider {

    @Getter
    private final String modelName;

    private final ModelConfig.ModelInfo config;
    private final LoadBalancer loadBalancer;
    private final FailoverHandler failoverHandler;

    public OpenAiCompatibleProvider(ModelConfig.ModelInfo config) {
        this.config = config;
        this.modelName = config.getName();
        this.loadBalancer = new LoadBalancer(config.getKeys());
        this.failoverHandler = new FailoverHandler(config.getMaxFailures());
        log.info("Provider [{}] initialized, {} keys, baseUrl={}, model={}",
                modelName, config.getKeys().size(), config.getBaseUrl(), config.getModelName());
    }

    @Override
    public boolean isAvailable() {
        return config.hasKeys() && !failoverHandler.isOpen();
    }

    @Override
    public String chat(String message) {
        String key = loadBalancer.next();
        OpenAiChatModel model = OpenAiChatModel.builder()
                .apiKey(key)
                .baseUrl(config.getBaseUrl())
                .modelName(config.getModelName())
                .temperature(config.getTemperature())
                .maxTokens(config.getMaxTokens())
                .timeout(Duration.ofSeconds(config.getTimeoutSeconds()))
                .build();

        log.debug("[{}] calling with key prefix: {}...", modelName, key.substring(0, 10));
        return model.chat(message);
    }

    @Override
    public void recordSuccess() {
        failoverHandler.recordSuccess();
    }

    @Override
    public boolean recordFailure() {
        boolean tripped = failoverHandler.recordFailure();
        if (tripped) {
            log.warn("[{}] Circuit breaker OPEN after {} consecutive failures", modelName, config.getMaxFailures());
        }
        return tripped;
    }
}

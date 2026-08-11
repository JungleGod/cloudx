package com.cloudx.aiagent.provider;

import com.cloudx.aiagent.config.ModelConfig;
import com.cloudx.aiagent.failover.FailoverHandler;
import com.cloudx.aiagent.loadbalance.LoadBalancer;
import dev.langchain4j.data.message.*;
import dev.langchain4j.model.openai.OpenAiChatModel;
import dev.langchain4j.model.openai.OpenAiStreamingChatModel;
import lombok.Getter;
import lombok.extern.slf4j.Slf4j;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;

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
                .frequencyPenalty(config.getFrequencyPenalty())
                .presencePenalty(config.getPresencePenalty())
                .timeout(Duration.ofSeconds(config.getTimeoutSeconds()))
                .build();

        log.debug("[{}] calling with key prefix: {}...", modelName, key.substring(0, 10));
        return model.chat(message);
    }

    @Override
    public void streamChat(String message, StreamCallback callback) {
        String key = loadBalancer.next();
        OpenAiStreamingChatModel model = OpenAiStreamingChatModel.builder()
                .apiKey(key)
                .baseUrl(config.getBaseUrl())
                .modelName(config.getModelName())
                .temperature(config.getTemperature())
                .maxTokens(config.getMaxTokens())
                .frequencyPenalty(config.getFrequencyPenalty())
                .presencePenalty(config.getPresencePenalty())
                .timeout(Duration.ofSeconds(config.getTimeoutSeconds()))
                .build();

        log.debug("[{}] streaming with key prefix: {}...", modelName, key.substring(0, 10));
        model.generate(message, new dev.langchain4j.model.StreamingResponseHandler<dev.langchain4j.data.message.AiMessage>() {
            @Override
            public void onNext(String token) {
                callback.onToken(token);
            }

            @Override
            public void onComplete(dev.langchain4j.model.output.Response<dev.langchain4j.data.message.AiMessage> response) {
                log.debug("[{}] stream complete, finishReason={}", modelName,
                        response.finishReason() != null ? response.finishReason().name() : "null");
                callback.onComplete();
            }

            @Override
            public void onError(Throwable error) {
                log.error("[{}] stream error: {}", modelName, error.getMessage());
                callback.onError(error);
            }
        });
    }

    @Override
    public String chatMultimodal(String text, List<String> base64Images) {
        String key = loadBalancer.next();
        OpenAiChatModel model = OpenAiChatModel.builder()
                .apiKey(key)
                .baseUrl(config.getBaseUrl())
                .modelName(config.getModelName())
                .temperature(config.getTemperature())
                .maxTokens(config.getMaxTokens())
                .frequencyPenalty(config.getFrequencyPenalty())
                .presencePenalty(config.getPresencePenalty())
                .timeout(Duration.ofSeconds(config.getTimeoutSeconds()))
                .build();

        List<Content> contents = new ArrayList<>();
        contents.add(TextContent.from(text));
        for (String img : base64Images) {
            contents.add(ImageContent.from(img));
        }
        UserMessage userMsg = UserMessage.from(contents);

        log.debug("[{}] multimodal call, text={}, images={}", modelName,
                text.length() > 50 ? text.substring(0, 50) + "..." : text, base64Images.size());
        return model.generate(List.of(userMsg)).content().text();
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
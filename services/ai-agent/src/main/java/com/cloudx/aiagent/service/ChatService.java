package com.cloudx.aiagent.service;

import dev.langchain4j.model.openai.OpenAiChatModel;
import jakarta.annotation.PostConstruct;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.time.Duration;

@Slf4j
@Service
public class ChatService {

    @Value("${langchain4j.open-ai.chat-model.api-key}")
    private String apiKey;

    @Value("${langchain4j.open-ai.chat-model.base-url}")
    private String baseUrl;

    @Value("${langchain4j.open-ai.chat-model.model-name}")
    private String modelName;

    @Value("${langchain4j.open-ai.chat-model.temperature}")
    private double temperature;

    @Value("${langchain4j.open-ai.chat-model.max-tokens}")
    private int maxTokens;

    @Value("${langchain4j.open-ai.chat-model.timeout}")
    private Duration timeout;

    private OpenAiChatModel model;

    @PostConstruct
    public void init() {
        log.info("Initializing DeepSeek chat model: baseUrl={}, model={}", baseUrl, modelName);
        this.model = OpenAiChatModel.builder()
                .apiKey(apiKey)
                .baseUrl(baseUrl)
                .modelName(modelName)
                .temperature(temperature)
                .maxTokens(maxTokens)
                .timeout(timeout)
                .build();
        log.info("DeepSeek chat model initialized successfully");
    }

    public String chat(String message) {
        log.debug("User message: {}", message);
        String reply = model.chat(message);
        log.debug("AI reply: {}", reply);
        return reply;
    }
}

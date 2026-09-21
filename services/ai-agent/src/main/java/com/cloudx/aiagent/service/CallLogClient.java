package com.cloudx.aiagent.service;

import com.cloudx.aiagent.config.InternalAuthInterceptor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.*;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestTemplate;

import java.util.Map;

/**
 * 调用 biz-service 记录调用日志
 */
@Slf4j
@Component
public class CallLogClient {

    private final RestTemplate restTemplate;
    private final String bizServiceUrl;

    public CallLogClient(@Value("${cloudx.biz-service.url:http://localhost:8081}") String bizServiceUrl,
                         InternalAuthInterceptor internalAuth) {
        this.restTemplate = new RestTemplate();
        this.restTemplate.getInterceptors().add(internalAuth);
        this.bizServiceUrl = bizServiceUrl;
    }

    public void record(Long userId, String model, String requestedModel, String requestBody, String responseBody,
                       int tokensInput, int tokensOutput, long latencyMs, boolean success, String errorMsg) {
        try {
            Map<String, Object> body = Map.of(
                    "userId", userId != null ? userId : 0,
                    "model", model,
                    "requestedModel", requestedModel != null ? requestedModel : "",
                    "requestBody", requestBody,
                    "responseBody", responseBody,
                    "tokensInput", tokensInput,
                    "tokensOutput", tokensOutput,
                    "latencyMs", latencyMs,
                    "status", success ? "success" : "fail",
                    "errorMsg", errorMsg != null ? errorMsg : ""
            );

            HttpHeaders headers = new HttpHeaders();
            headers.setContentType(MediaType.APPLICATION_JSON);
            HttpEntity<Map<String, Object>> request = new HttpEntity<>(body, headers);

            restTemplate.postForEntity(bizServiceUrl + "/api/internal/call-logs", request, String.class);
        } catch (Exception e) {
            log.error("Failed to record call log: {}", e.getMessage());
        }
    }
}

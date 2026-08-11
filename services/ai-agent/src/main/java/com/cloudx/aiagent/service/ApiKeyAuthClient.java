package com.cloudx.aiagent.service;

import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.*;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestTemplate;

import java.util.Map;

/**
 * 调用 biz-service 验证 API Key
 */
@Slf4j
@Component
public class ApiKeyAuthClient {

    private final RestTemplate restTemplate;
    private final String bizServiceUrl;

    public ApiKeyAuthClient(@Value("${cloudx.biz-service.url:http://localhost:8081}") String bizServiceUrl) {
        this.restTemplate = new RestTemplate();
        this.bizServiceUrl = bizServiceUrl;
    }

    /**
     * 验证 API Key（Bearer Token）
     *
     * @param bearerToken 完整的 "Bearer sk-xxx" 或纯 "sk-xxx"
     * @return AuthResult 包含 userId 和 keyId，验证失败返回 null
     */
    public AuthResult verify(String bearerToken) {
        try {
            // 去掉 "Bearer " 前缀
            String token = bearerToken;
            if (token.startsWith("Bearer ")) {
                token = token.substring(7);
            }
            token = token.trim();

            if (token.isBlank()) {
                return null;
            }

            Map<String, String> body = Map.of("secretKey", token);

            HttpHeaders headers = new HttpHeaders();
            headers.setContentType(MediaType.APPLICATION_JSON);
            HttpEntity<Map<String, String>> request = new HttpEntity<>(body, headers);

            ResponseEntity<Map> response = restTemplate.postForEntity(
                    bizServiceUrl + "/api/internal/keys/verify", request, Map.class);

            if (response.getStatusCode().is2xxSuccessful() && response.getBody() != null) {
                Map<String, Object> data = (Map<String, Object>) response.getBody().get("data");
                if (data != null) {
                    Long userId = data.get("userId") != null
                            ? ((Number) data.get("userId")).longValue() : null;
                    Long keyId = data.get("keyId") != null
                            ? ((Number) data.get("keyId")).longValue() : null;
                    return new AuthResult(userId, keyId);
                }
            }
        } catch (Exception e) {
            log.warn("API Key verification failed: {}", e.getMessage());
        }
        return null;
    }

    /**
     * 验证结果
     */
    public record AuthResult(Long userId, Long keyId) {}
}

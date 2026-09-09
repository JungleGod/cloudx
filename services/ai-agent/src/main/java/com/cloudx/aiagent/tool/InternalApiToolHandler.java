package com.cloudx.aiagent.tool;

import lombok.extern.slf4j.Slf4j;
import org.springframework.http.*;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestTemplate;

import java.util.Map;

/**
 * 内部 API 工具处理器 — 调用 biz-service 内部接口
 * 自动传递用户身份（X-User-Id, X-User-Role）
 */
@Slf4j
@Component
public class InternalApiToolHandler {

    private final RestTemplate restTemplate = new RestTemplate();
    private final String bizServiceUrl = "http://localhost:8081";

    /**
     * 执行内部 API 调用
     * @param tool 工具定义（internal_path 指定 biz-service API 路径）
     * @param argumentsJson 参数 JSON
     * @param callerRole 调用者角色
     */
    @SuppressWarnings("unchecked")
    public String execute(ToolDefinition tool, String argumentsJson, String callerRole, Long userId) {
        String path = tool.getInternalPath();
        String url = bizServiceUrl + path;

        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.APPLICATION_JSON);
        headers.set("X-User-Role", callerRole != null ? callerRole : "user");
        if (userId != null) {
            headers.set("X-User-Id", String.valueOf(userId));
        }

        try {
            // 解析参数
            Map<String, Object> args = new com.fasterxml.jackson.databind.ObjectMapper()
                    .readValue(argumentsJson, Map.class);

            HttpEntity<Map<String, Object>> entity = new HttpEntity<>(args, headers);
            log.info("Internal API tool [{}] POST {}", tool.getName(), url);

            ResponseEntity<String> response = restTemplate.exchange(url, HttpMethod.POST, entity, String.class);

            if (response.getStatusCode().is2xxSuccessful() && response.getBody() != null) {
                return response.getBody();
            }
            return "{\"error\": \"Internal API returned " + response.getStatusCodeValue() + "\"}";
        } catch (Exception e) {
            log.error("Internal API tool [{}] failed: {}", tool.getName(), e.getMessage());
            return "{\"error\": \"" + e.getMessage() + "\"}";
        }
    }
}

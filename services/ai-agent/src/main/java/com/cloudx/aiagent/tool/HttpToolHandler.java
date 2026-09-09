package com.cloudx.aiagent.tool;

import lombok.extern.slf4j.Slf4j;
import org.springframework.http.*;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestTemplate;

import java.util.Map;

/**
 * HTTP 工具处理器 — 执行外部 HTTP 调用
 * 支持 URL 模板变量替换（{varName} 从 arguments 中取值）
 */
@Slf4j
@Component
public class HttpToolHandler {

    private final RestTemplate restTemplate = new RestTemplate();

    public String execute(ToolDefinition tool, String argumentsJson) {
        String url = resolveUrl(tool.getHttpUrl(), argumentsJson);
        HttpMethod method = HttpMethod.valueOf(tool.getHttpMethod().toUpperCase());

        // 构建请求
        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.APPLICATION_JSON);
        if (tool.getHttpHeaders() != null) {
            tool.getHttpHeaders().forEach(headers::set);
        }

        HttpEntity<String> entity;
        if (method == HttpMethod.GET || method == HttpMethod.DELETE) {
            entity = new HttpEntity<>(headers);
        } else {
            entity = new HttpEntity<>(argumentsJson, headers);
        }

        log.info("HTTP tool [{}] {} {}", tool.getName(), method, url);
        ResponseEntity<String> response = restTemplate.exchange(url, method, entity, String.class);

        if (response.getStatusCode().is2xxSuccessful() && response.getBody() != null) {
            return response.getBody();
        }
        return "{\"error\": \"HTTP " + response.getStatusCodeValue() + "\"}";
    }

    /** 替换 URL 模板中的 {varName} */
    private String resolveUrl(String urlTemplate, String argumentsJson) {
        if (urlTemplate == null || !urlTemplate.contains("{")) {
            return urlTemplate;
        }
        try {
            @SuppressWarnings("unchecked")
            Map<String, Object> args = new com.fasterxml.jackson.databind.ObjectMapper()
                    .readValue(argumentsJson, Map.class);
            String resolved = urlTemplate;
            for (Map.Entry<String, Object> entry : args.entrySet()) {
                resolved = resolved.replace("{" + entry.getKey() + "}",
                        entry.getValue() != null ? entry.getValue().toString() : "");
            }
            return resolved;
        } catch (Exception e) {
            log.warn("Failed to resolve URL template: {}", e.getMessage());
            return urlTemplate;
        }
    }
}

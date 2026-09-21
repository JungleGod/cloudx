package com.cloudx.aiagent.service;

import com.cloudx.aiagent.config.InternalAuthInterceptor;
import com.cloudx.aiagent.config.ModelConfig;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.ParameterizedTypeReference;
import org.springframework.http.HttpMethod;
import org.springframework.http.ResponseEntity;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.http.converter.StringHttpMessageConverter;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestTemplate;

import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;

/**
 * 调用 biz-service 拉取模型配置（含解密后的上游 Key）。
 * <p>
 * biz-service 返回统一 R 包裹 {@code {code, msg, data}}，且 R 无默认构造器，
 * 因此这里先反序列化为 Map，再逐条 convertValue 成 ModelInfo。
 * 失败抛异常，由 ModelRouter.resolveModels 兜底。
 * </p>
 */
@Slf4j
@Component
public class ModelConfigClient {

    private final RestTemplate restTemplate;
    private final ObjectMapper objectMapper = new ObjectMapper();
    private final String bizServiceUrl;

    public ModelConfigClient(@Value("${cloudx.biz-service.url:http://localhost:8081}") String bizServiceUrl,
                             InternalAuthInterceptor internalAuth) {
        SimpleClientHttpRequestFactory factory = new SimpleClientHttpRequestFactory();
        factory.setConnectTimeout(3000);
        factory.setReadTimeout(3000);
        this.restTemplate = new RestTemplate(factory);
        this.restTemplate.getMessageConverters().forEach(c -> {
            if (c instanceof StringHttpMessageConverter s) {
                s.setDefaultCharset(StandardCharsets.UTF_8);
            }
        });
        this.restTemplate.getInterceptors().add(internalAuth);
        this.bizServiceUrl = bizServiceUrl;
    }

    /** 拉取全部模型配置 */
    public List<ModelConfig.ModelInfo> fetch() {
        ResponseEntity<Map<String, Object>> resp = restTemplate.exchange(
                bizServiceUrl + "/api/internal/models", HttpMethod.GET, null,
                new ParameterizedTypeReference<Map<String, Object>>() {});

        Map<String, Object> body = resp.getBody();
        if (body == null || !Integer.valueOf(200).equals(body.get("code"))) {
            throw new IllegalStateException("biz-service /api/internal/models returned non-OK: " + body);
        }
        if (!(body.get("data") instanceof List<?> list)) {
            return List.of();
        }
        return list.stream()
                .map(item -> objectMapper.convertValue(item, ModelConfig.ModelInfo.class))
                .toList();
    }
}

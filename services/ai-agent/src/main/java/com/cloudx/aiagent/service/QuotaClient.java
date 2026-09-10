package com.cloudx.aiagent.service;

import com.cloudx.common.exception.QuotaExceededException;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.*;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestTemplate;

import java.util.Map;

/**
 * 调用 biz-service 校验用户本月额度（调用模型前拦截）。
 * <p>
 * 失败策略：biz-service 不可用时「放行」（fail-open），避免额度服务故障拖垮整个网关；
 * 仅当 biz-service 明确返回 exceeded=true 时抛 {@link QuotaExceededException}。
 * </p>
 */
@Slf4j
@Component
public class QuotaClient {

    private final RestTemplate restTemplate;
    private final String bizServiceUrl;

    public QuotaClient(@Value("${cloudx.biz-service.url:http://localhost:8081}") String bizServiceUrl) {
        this.restTemplate = new RestTemplate();
        this.bizServiceUrl = bizServiceUrl;
    }

    /**
     * 校验额度，超限抛 {@link QuotaExceededException}。
     * userId 为空表示未鉴权（直连场景），跳过校验。
     */
    public void checkQuota(Long userId) {
        if (userId == null || userId <= 0) {
            return;
        }
        try {
            HttpHeaders headers = new HttpHeaders();
            headers.setContentType(MediaType.APPLICATION_JSON);
            HttpEntity<Map<String, Object>> request = new HttpEntity<>(Map.of("userId", userId), headers);

            ResponseEntity<Map> response = restTemplate.postForEntity(
                    bizServiceUrl + "/api/internal/quota/check", request, Map.class);

            if (response.getStatusCode().is2xxSuccessful() && response.getBody() != null) {
                Map<String, Object> data = (Map<String, Object>) response.getBody().get("data");
                if (data != null) {
                    Boolean unlimited = (Boolean) data.get("unlimited");
                    Boolean exceeded = (Boolean) data.get("exceeded");
                    if (Boolean.TRUE.equals(exceeded) && !Boolean.TRUE.equals(unlimited)) {
                        throw new QuotaExceededException("本月额度已用完，下月1号自动恢复");
                    }
                }
            }
        } catch (QuotaExceededException e) {
            throw e;
        } catch (Exception e) {
            log.warn("Quota check failed, allow pass: {}", e.getMessage());
        }
    }
}
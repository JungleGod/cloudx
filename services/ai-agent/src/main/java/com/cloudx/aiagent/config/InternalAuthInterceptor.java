package com.cloudx.aiagent.config;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpRequest;
import org.springframework.http.client.ClientHttpRequestExecution;
import org.springframework.http.client.ClientHttpRequestInterceptor;
import org.springframework.http.client.ClientHttpResponse;
import org.springframework.stereotype.Component;

import java.io.IOException;

/**
 * 内部调用 Token 拦截器 — ai-agent 调 biz-service /api/internal/** 时携带共享密钥。
 * <p>
 * gateway 已不路由 /api/internal/**（公网不可达），此 Token 用于防御同机/内网直连 8081 的越权访问。
 * 本地开发用默认值即可；生产必须通过 INTERNAL_TOKEN 环境变量（或 Nacos 配置）设置强随机值，
 * 且与 biz-service 侧保持一致，否则所有服务间调用会被 403 拒绝。
 * </p>
 */
@Component
public class InternalAuthInterceptor implements ClientHttpRequestInterceptor {

    public static final String HEADER_NAME = "X-Internal-Token";

    private final String token;

    public InternalAuthInterceptor(
            @Value("${cloudx.internal-token:${INTERNAL_TOKEN:cloudx-internal-dev-2026}}") String token) {
        this.token = token;
    }

    @Override
    public ClientHttpResponse intercept(HttpRequest request, byte[] body, ClientHttpRequestExecution execution)
            throws IOException {
        request.getHeaders().set(HEADER_NAME, token);
        return execution.execute(request, body);
    }
}
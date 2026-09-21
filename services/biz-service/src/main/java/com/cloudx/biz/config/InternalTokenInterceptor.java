package com.cloudx.biz.config;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import org.springframework.web.servlet.HandlerInterceptor;

/**
 * 内部接口 Token 校验 — /api/internal/** 仅接受携带 X-Internal-Token 的服务间调用。
 * <p>
 * gateway 已不路由 /api/internal/**（防止公网穿透），此拦截器进一步防御内网直连 8081。
 * 本地开发用默认值即可；生产必须通过 INTERNAL_TOKEN 环境变量（或 Nacos 配置）设置强随机值，
 * 且与 ai-agent 侧保持一致，否则服务间调用会被 403 拒绝。
 * </p>
 */
@Slf4j
@Component
public class InternalTokenInterceptor implements HandlerInterceptor {

    private final String token;

    public InternalTokenInterceptor(
            @Value("${cloudx.internal-token:${INTERNAL_TOKEN:cloudx-internal-dev-2026}}") String token) {
        this.token = token;
    }

    @Override
    public boolean preHandle(HttpServletRequest request, HttpServletResponse response, Object handler) throws Exception {
        if (token.equals(request.getHeader("X-Internal-Token"))) {
            return true;
        }
        log.warn("Internal API rejected: {} {} from {}",
                request.getMethod(), request.getRequestURI(), request.getRemoteAddr());
        response.setStatus(HttpServletResponse.SC_FORBIDDEN);
        response.setContentType("application/json;charset=UTF-8");
        response.getWriter().write("{\"code\":403,\"msg\":\"internal token missing or invalid\",\"data\":null}");
        return false;
    }
}
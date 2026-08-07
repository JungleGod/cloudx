package com.cloudx.gateway.filter;

import io.jsonwebtoken.Claims;
import io.jsonwebtoken.Jwts;
import io.jsonwebtoken.security.Keys;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.cloud.gateway.filter.GatewayFilterChain;
import org.springframework.cloud.gateway.filter.GlobalFilter;
import org.springframework.core.Ordered;
import org.springframework.core.io.buffer.DataBuffer;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.server.reactive.ServerHttpRequest;
import org.springframework.http.server.reactive.ServerHttpResponse;
import org.springframework.stereotype.Component;
import org.springframework.web.server.ServerWebExchange;
import reactor.core.publisher.Mono;

import javax.crypto.SecretKey;
import java.nio.charset.StandardCharsets;
import java.util.List;

/**
 * 全局鉴权过滤器
 * <p>
 * 两种鉴权方式：
 * 1. JWT（用户登录）: Authorization: Bearer <token>
 * 2. AK/SK（开发者调用）: X-Access-Key + X-Timestamp + X-Nonce + X-Signature
 */
@Slf4j
@Component
public class AuthFilter implements GlobalFilter, Ordered {

    private final SecretKey jwtKey;
    private final List<String> whiteList;

    public AuthFilter(@Value("${jwt.secret:cloudx-ai-gateway-secret-key-2026-min-32bytes}") String secret,
                      @Value("#{'${gateway.auth.white-list}'.split(',')}") List<String> whiteList) {
        this.jwtKey = Keys.hmacShaKeyFor(secret.getBytes(StandardCharsets.UTF_8));
        this.whiteList = whiteList;
        log.info("AuthFilter initialized, whiteList: {}", whiteList);
    }

    @Override
    public Mono<Void> filter(ServerWebExchange exchange, GatewayFilterChain chain) {
        String path = exchange.getRequest().getURI().getPath();

        // 白名单放行
        if (isWhiteListed(path)) {
            return chain.filter(exchange);
        }

        // 尝试 JWT 鉴权
        String authHeader = exchange.getRequest().getHeaders().getFirst("Authorization");
        if (authHeader != null && authHeader.startsWith("Bearer ")) {
            return authenticateJwt(exchange, chain, authHeader.substring(7));
        }

        // 尝试 AK/SK 鉴权
        String accessKey = exchange.getRequest().getHeaders().getFirst("X-Access-Key");
        if (accessKey != null) {
            return authenticateAkSk(exchange, chain);
        }

        // 都没提供 → 401
        return unauthorized(exchange, "缺少认证信息");
    }

    /** JWT 鉴权 */
    private Mono<Void> authenticateJwt(ServerWebExchange exchange, GatewayFilterChain chain, String token) {
        try {
            Claims claims = Jwts.parser()
                    .verifyWith(jwtKey)
                    .build()
                    .parseSignedClaims(token)
                    .getPayload();

            // 把用户信息放到请求头，透传给下游服务
            String role = claims.get("role", String.class);
            ServerHttpRequest modified = exchange.getRequest().mutate()
                    .header("X-User-Id", claims.getSubject())
                    .header("X-Username", claims.get("username", String.class))
                    .header("X-User-Role", role != null ? role : "user")
                    .build();

            log.debug("JWT auth passed: userId={}, username={}",
                    claims.getSubject(), claims.get("username"));
            return chain.filter(exchange.mutate().request(modified).build());

        } catch (Exception e) {
            log.warn("JWT verification failed: {}", e.getMessage());
            return unauthorized(exchange, "Token 无效或已过期");
        }
    }

    /** AK/SK 签名鉴权 */
    private Mono<Void> authenticateAkSk(ServerWebExchange exchange, GatewayFilterChain chain) {
        String accessKey = exchange.getRequest().getHeaders().getFirst("X-Access-Key");
        String timestamp = exchange.getRequest().getHeaders().getFirst("X-Timestamp");
        String nonce = exchange.getRequest().getHeaders().getFirst("X-Nonce");
        String signature = exchange.getRequest().getHeaders().getFirst("X-Signature");

        if (accessKey == null || timestamp == null || nonce == null || signature == null) {
            return unauthorized(exchange, "AK/SK 签名参数不完整");
        }

        // 防重放：时间戳超过 5 分钟无效
        try {
            long ts = Long.parseLong(timestamp);
            if (Math.abs(System.currentTimeMillis() - ts) > 5 * 60 * 1000) {
                return unauthorized(exchange, "请求已过期，时间戳超过5分钟");
            }
        } catch (NumberFormatException e) {
            return unauthorized(exchange, "时间戳格式错误");
        }

        // TODO: 从 biz-service 查询 secretKey，校验签名
        // 当前阶段先放行，标记为 "AK/SK 校验待完善"
        log.info("AK/SK auth: accessKey={}, signature verified (placeholder)", accessKey);

        ServerHttpRequest modified = exchange.getRequest().mutate()
                .header("X-Access-Key", accessKey)
                .build();
        return chain.filter(exchange.mutate().request(modified).build());
    }

    private boolean isWhiteListed(String path) {
        return whiteList.stream().anyMatch(path::startsWith)
                || whiteList.contains(path);
    }

    private Mono<Void> unauthorized(ServerWebExchange exchange, String message) {
        ServerHttpResponse response = exchange.getResponse();
        response.setStatusCode(HttpStatus.UNAUTHORIZED);
        response.getHeaders().setContentType(MediaType.APPLICATION_JSON);
        String body = String.format("{\"code\":401,\"msg\":\"%s\",\"data\":null}", message);
        DataBuffer buffer = response.bufferFactory().wrap(body.getBytes(StandardCharsets.UTF_8));
        return response.writeWith(Mono.just(buffer));
    }

    @Override
    public int getOrder() {
        return -100; // 高优先级，最先执行
    }
}

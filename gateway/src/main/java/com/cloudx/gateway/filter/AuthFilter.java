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
import org.springframework.data.redis.core.ReactiveStringRedisTemplate;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.server.reactive.ServerHttpRequest;
import org.springframework.http.server.reactive.ServerHttpResponse;
import org.springframework.stereotype.Component;
import org.springframework.web.server.ServerWebExchange;
import reactor.core.publisher.Mono;

import javax.crypto.Mac;
import javax.crypto.SecretKey;
import javax.crypto.spec.SecretKeySpec;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Duration;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;

/**
 * 全局鉴权过滤器
 * <p>
 * 两种鉴权方式：
 * 1. JWT（用户登录）: Authorization: Bearer <token>
 * 2. AK/SK（开发者调用）: X-Access-Key + X-Timestamp + X-Nonce + X-Signature
 * <pre>
 * AK/SK 签名规范（与客户端约定）：
 *   stringToSign = METHOD + "\n" + PATH + "\n" + ACCESS_KEY + "\n" + TIMESTAMP + "\n" + NONCE
 *   X-Signature  = 小写 Hex( HMAC-SHA256( stringToSign, SecretKey ) )
 *   TIMESTAMP    = 毫秒级 Unix 时间，偏差超过 5 分钟拒绝（防重放第一层）
 *   NONCE        = 每次请求唯一的随机串，Redis SETNX 5 分钟去重（防重放第二层）
 * SecretKey 不上网传输：gateway 经内网接口从 biz-service 按 AccessKey 取回（AES 密文解密），
 * 在网关本地完成 HMAC 计算与恒时比较。
 * </pre>
 */
@Slf4j
@Component
public class AuthFilter implements GlobalFilter, Ordered {

    private final SecretKey jwtKey;
    private final List<String> whiteList;
    private final ReactiveStringRedisTemplate redis;
    private final org.springframework.web.reactive.function.client.WebClient webClient;
    private final String internalToken;

    /** AK/SK 防重放：时间戳允许偏差 */
    private static final long TIMESTAMP_TOLERANCE_MS = 5 * 60 * 1000;
    /** nonce 去重窗口 = 时间戳窗口（窗口外时间戳校验本身就会拒绝） */
    private static final Duration NONCE_TTL = Duration.ofMillis(TIMESTAMP_TOLERANCE_MS);

    public AuthFilter(@Value("${jwt.secret:cloudx-ai-gateway-secret-key-2026-min-32bytes}") String secret,
                      @Value("#{'${gateway.auth.white-list}'.split(',')}") List<String> whiteList,
                      @Value("${cloudx.internal-token:${INTERNAL_TOKEN:cloudx-internal-dev-2026}}") String internalToken,
                      ReactiveStringRedisTemplate redis,
                      org.springframework.web.reactive.function.client.WebClient.Builder webClientBuilder) {
        this.jwtKey = Keys.hmacShaKeyFor(secret.getBytes(StandardCharsets.UTF_8));
        this.whiteList = whiteList;
        this.internalToken = internalToken;
        this.redis = redis;
        // baseUrl 用服务名：经 Nacos 服务发现 + LoadBalancer 解析（lb:// 语义）
        this.webClient = webClientBuilder.baseUrl("http://biz-service").build();
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

    /**
     * AK/SK 签名鉴权（全链路响应式，fail-closed：任何一环失败都拒绝，不降级放行）
     * <ol>
     *   <li>四头齐全 + 时间戳 ±5 分钟窗口（挡重放的旧请求）</li>
     *   <li>经内网从 biz-service 按 AccessKey 取回 SecretKey</li>
     *   <li>本地计算 HMAC-SHA256，与 X-Signature 恒时比较</li>
     *   <li>Redis SETNX 标记 nonce（挡窗口内的重发），占位成功才放行</li>
     * </ol>
     * nonce 标记放在签名校验之后：垃圾请求进不了 Redis，不会被用来耗尽 nonce 空间
     */
    private Mono<Void> authenticateAkSk(ServerWebExchange exchange, GatewayFilterChain chain) {
        String accessKey = exchange.getRequest().getHeaders().getFirst("X-Access-Key");
        String timestamp = exchange.getRequest().getHeaders().getFirst("X-Timestamp");
        String nonce = exchange.getRequest().getHeaders().getFirst("X-Nonce");
        String signature = exchange.getRequest().getHeaders().getFirst("X-Signature");

        if (accessKey == null || accessKey.isBlank()
                || timestamp == null || timestamp.isBlank()
                || nonce == null || nonce.isBlank()
                || signature == null || signature.isBlank()) {
            return unauthorized(exchange, "AK/SK 签名参数不完整");
        }

        // 防重放第一层：时间戳窗口
        long ts;
        try {
            ts = Long.parseLong(timestamp.trim());
        } catch (NumberFormatException e) {
            return unauthorized(exchange, "时间戳格式错误");
        }
        if (Math.abs(System.currentTimeMillis() - ts) > TIMESTAMP_TOLERANCE_MS) {
            return unauthorized(exchange, "请求已过期，时间戳超过5分钟");
        }

        // 组装待签名字符串：METHOD \n PATH \n ACCESS_KEY \n TIMESTAMP \n NONCE
        String stringToSign = exchange.getRequest().getMethod().name()
                + "\n" + exchange.getRequest().getURI().getPath()
                + "\n" + accessKey
                + "\n" + timestamp
                + "\n" + nonce;

        return fetchSecretKey(accessKey)
                .flatMap(secret -> {
                    if (!secret.valid()) {
                        return unauthorized(exchange, "AccessKey 无效或已禁用");
                    }
                    if (!signatureMatches(secret.secretKey(), stringToSign, signature)) {
                        log.warn("AK/SK signature mismatch: accessKey={}, path={}", accessKey,
                                exchange.getRequest().getURI().getPath());
                        return unauthorized(exchange, "签名校验失败");
                    }
                    // 防重放第二层：nonce 一次性
                    return markNonceOnce(accessKey, nonce)
                            .flatMap(accepted -> {
                                if (!Boolean.TRUE.equals(accepted)) {
                                    return unauthorized(exchange, "请求重放（nonce 已使用）");
                                }
                                return proceedAk(exchange, chain, secret, accessKey);
                            });
                })
                .onErrorResume(e -> {
                    log.error("AK/SK auth: biz-service unreachable: {}", e.getMessage());
                    // 取不到密钥无法验签，安全上必须 fail-closed（与额度检查的 fail-open 互补：
                    // 鉴权决定"谁能进"不能放，额度决定"花多少钱"可以放）
                    return serviceUnavailable(exchange, "鉴权服务不可用");
                });
    }

    /** 从 biz-service 内网接口取 AccessKey 对应的密钥信息（AES 密文由 biz 侧解密） */
    private Mono<AkSecret> fetchSecretKey(String accessKey) {
        return webClient.post()
                .uri("/api/internal/keys/ak")
                .header("X-Internal-Token", internalToken)
                .contentType(MediaType.APPLICATION_JSON)
                .bodyValue(Map.of("accessKey", accessKey))
                .retrieve()
                .bodyToMono(Map.class)
                .map(AuthFilter::toAkSecret);
    }

    @SuppressWarnings("unchecked")
    private static AkSecret toAkSecret(Map<String, Object> body) {
        Object dataObj = body == null ? null : body.get("data");
        if (!(dataObj instanceof Map)) {
            return AkSecret.invalid();
        }
        Map<String, Object> data = (Map<String, Object>) dataObj;
        if (!Boolean.TRUE.equals(data.get("valid"))) {
            return AkSecret.invalid();
        }
        return new AkSecret(true,
                data.get("userId") instanceof Number n ? n.longValue() : null,
                data.get("keyId") instanceof Number n ? n.longValue() : null,
                data.get("secretKey") != null ? data.get("secretKey").toString() : null,
                data.get("role") != null ? data.get("role").toString() : "user");
    }

    /** HMAC-SHA256 计算与恒时比较（MessageDigest.isEqual 防时序攻击） */
    private boolean signatureMatches(String secretKey, String stringToSign, String expected) {
        try {
            Mac mac = Mac.getInstance("HmacSHA256");
            mac.init(new SecretKeySpec(secretKey.getBytes(StandardCharsets.UTF_8), "HmacSHA256"));
            String actualHex = HexFormat.of().formatHex(
                    mac.doFinal(stringToSign.getBytes(StandardCharsets.UTF_8)));
            return MessageDigest.isEqual(
                    actualHex.getBytes(StandardCharsets.UTF_8),
                    expected.trim().getBytes(StandardCharsets.UTF_8));
        } catch (Exception e) {
            log.error("HMAC-SHA256 computation failed: {}", e.getMessage());
            return false;
        }
    }

    /** nonce 占位：SETNX + TTL，返回 true = 首次使用 */
    private Mono<Boolean> markNonceOnce(String accessKey, String nonce) {
        return redis.opsForValue()
                .setIfAbsent("cloudx:aksk:nonce:" + accessKey + ":" + nonce, "1", NONCE_TTL);
    }

    /** 验签通过：透传身份头给下游（与 JWT 路径的 X-User-* 头语义对齐） */
    private Mono<Void> proceedAk(ServerWebExchange exchange, GatewayFilterChain chain,
                                 AkSecret secret, String accessKey) {
        ServerHttpRequest modified = exchange.getRequest().mutate()
                .header("X-Access-Key", accessKey)
                .header("X-User-Id", String.valueOf(secret.userId()))
                .header("X-Key-Id", String.valueOf(secret.keyId()))
                .header("X-User-Role", secret.role() != null ? secret.role() : "user")
                .build();
        log.debug("AK/SK auth passed: accessKey={}, userId={}", accessKey, secret.userId());
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

    private Mono<Void> serviceUnavailable(ServerWebExchange exchange, String message) {
        ServerHttpResponse response = exchange.getResponse();
        response.setStatusCode(HttpStatus.SERVICE_UNAVAILABLE);
        response.getHeaders().setContentType(MediaType.APPLICATION_JSON);
        String body = String.format("{\"code\":503,\"msg\":\"%s\",\"data\":null}", message);
        DataBuffer buffer = response.bufferFactory().wrap(body.getBytes(StandardCharsets.UTF_8));
        return response.writeWith(Mono.just(buffer));
    }

    @Override
    public int getOrder() {
        return -100; // 高优先级，最先执行
    }

    /** biz-service /api/internal/keys/ak 返回的密钥信息 */
    private record AkSecret(boolean valid, Long userId, Long keyId, String secretKey, String role) {
        static AkSecret invalid() {
            return new AkSecret(false, null, null, null, null);
        }
    }
}
package com.cloudx.gateway.config;

import org.springframework.cloud.client.loadbalancer.LoadBalanced;
import org.springframework.cloud.gateway.filter.ratelimit.KeyResolver;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.reactive.function.client.WebClient;
import reactor.core.publisher.Mono;

/**
 * Gateway 配置 — 限流 Key 解析器 + 负载均衡 WebClient
 */
@Configuration
public class GatewayConfig {

    /** 按 IP 限流 */
    @Bean
    public KeyResolver ipKeyResolver() {
        return exchange -> {
            String ip = exchange.getRequest().getRemoteAddress().getAddress().getHostAddress();
            return Mono.just(ip);
        };
    }

    /** 负载均衡 WebClient：baseUrl 里写服务名（http://biz-service），经 Nacos 服务发现解析。
     *  AK/SK 验签需按 AccessKey 从 biz-service 取 SecretKey，WebFlux 里远程调用必须用 WebClient */
    @Bean
    @LoadBalanced
    public WebClient.Builder loadBalancedWebClientBuilder() {
        return WebClient.builder();
    }
}

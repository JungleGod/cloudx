package com.cloudx.biz.controller;

import com.cloudx.common.result.R;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.cloud.context.config.annotation.RefreshScope;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

import java.time.LocalDateTime;
import java.util.Map;

@RefreshScope
@RestController
public class HealthController {

    @Value("${server.port}")
    private int port;

    @GetMapping("/health")
    public R<Map<String, Object>> health() {
        return R.ok(Map.of(
                "service", "biz-service",
                "port", port,
                "time", LocalDateTime.now().toString(),
                "status", "UP"
        ));
    }
}

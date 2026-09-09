package com.cloudx.aiagent.loadbalance;

import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * 轮询负载均衡器 — 同一模型的多个 API Key 轮流使用
 */
public class LoadBalancer {

    private final List<String> keys;
    private final AtomicInteger index = new AtomicInteger(0);

    public LoadBalancer(List<String> keys) {
        this.keys = keys.stream()
                .filter(k -> k != null && !k.isBlank())
                .toList();
        if (this.keys.isEmpty()) {
            throw new IllegalArgumentException("至少需要一个有效的 API Key");
        }
    }

    /** 轮询获取下一个 Key */
    public String next() {
        int idx = Math.abs(index.getAndIncrement() % keys.size());
        return keys.get(idx);
    }

    public int keyCount() {
        return keys.size();
    }
}

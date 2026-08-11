package com.cloudx.biz.controller;

import com.cloudx.biz.entity.ApiKey;
import com.cloudx.biz.service.ApiKeyService;
import com.cloudx.biz.service.CallLogService;
import com.cloudx.common.result.R;
import lombok.RequiredArgsConstructor;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.web.bind.annotation.*;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;

@RestController
@RequiredArgsConstructor
public class CallLogController {

    private final CallLogService callLogService;
    private final ApiKeyService apiKeyService;

    /** 内部接口：ai-agent 记录调用日志 */
    @PostMapping("/api/internal/call-logs")
    public R<Void> record(@RequestBody Map<String, Object> body) {
        Long userId = toLong(body.get("userId"));
        String model = (String) body.getOrDefault("model", "unknown");
        String requestBody = (String) body.getOrDefault("requestBody", "");
        String responseBody = (String) body.getOrDefault("responseBody", "");
        int tokensInput = toInt(body.get("tokensInput"));
        int tokensOutput = toInt(body.get("tokensOutput"));
        long latencyMs = toLong(body.get("latencyMs"));
        boolean success = "success".equals(body.get("status"));
        String errorMsg = (String) body.getOrDefault("errorMsg", "");

        // apiKeyId/interfaceId 传 0：在线调试等场景没有 API Key，用 0 表示非 Key 调用
        callLogService.record(userId, 0L, null, model,
                requestBody, responseBody, tokensInput, tokensOutput, latencyMs, success, errorMsg);
        return R.ok();
    }

    /** 内部接口：验证 API Key（被 ai-agent 调用） */
    @PostMapping("/api/internal/keys/verify")
    public R<Map<String, Object>> verify(@RequestBody Map<String, String> body) {
        String secretKey = body.get("secretKey");
        ApiKey key = apiKeyService.verifyBySecretKey(secretKey);
        return R.ok(Map.of(
                "userId", key.getUserId(),
                "keyId", key.getId()
        ));
    }

    /** 今日统计 */
    @GetMapping("/api/stats/today")
    public R<Map<String, Object>> today(@RequestParam(required = false) Long userId) {
        return R.ok(callLogService.statsToday(userId));
    }

    /** 按模型统计 */
    @GetMapping("/api/stats/by-model")
    public R<List<Map<String, Object>>> byModel(@RequestParam(defaultValue = "7") int days) {
        return R.ok(callLogService.statsByModel(days));
    }

    /** 每日统计 */
    @GetMapping("/api/stats/daily")
    public R<List<Map<String, Object>>> daily(@RequestParam(defaultValue = "30") int days) {
        return R.ok(callLogService.statsDaily(days));
    }

    private Long toLong(Object obj) {
        if (obj == null) return 0L;
        if (obj instanceof Number n) return n.longValue();
        try { return Long.parseLong(obj.toString()); } catch (Exception e) { return 0L; }
    }

    private int toInt(Object obj) {
        if (obj == null) return 0;
        if (obj instanceof Number n) return n.intValue();
        try { return Integer.parseInt(obj.toString()); } catch (Exception e) { return 0; }
    }
}

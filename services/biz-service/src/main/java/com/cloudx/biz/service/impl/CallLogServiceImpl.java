package com.cloudx.biz.service.impl;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import com.cloudx.biz.entity.CallLog;
import com.cloudx.biz.mapper.CallLogMapper;
import com.cloudx.biz.service.CallLogService;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.util.*;

@Service
@RequiredArgsConstructor
public class CallLogServiceImpl extends ServiceImpl<CallLogMapper, CallLog> implements CallLogService {

    /** 各模型定价：元/千token（可迁移到 Nacos 配置中心） */
    private static final Map<String, BigDecimal[]> PRICING = Map.of(
            "deepseek", new BigDecimal[]{new BigDecimal("0.001"), new BigDecimal("0.002")},
            "qwen", new BigDecimal[]{new BigDecimal("0.003"), new BigDecimal("0.006")},
            "gpt-4o", new BigDecimal[]{new BigDecimal("0.15"), new BigDecimal("0.60")}
    );

    @Override
    public void record(Long userId, Long apiKeyId, Long interfaceId, String model,
                       String requestBody, String responseBody,
                       int tokensInput, int tokensOutput,
                       long latencyMs, boolean success, String errorMsg) {
        int total = tokensInput + tokensOutput;
        BigDecimal cost = BigDecimal.ZERO;
        if (success && total > 0) {
            BigDecimal[] prices = PRICING.getOrDefault(model.split("-")[0],
                    new BigDecimal[]{new BigDecimal("0.001"), new BigDecimal("0.002")});
            cost = prices[0].multiply(new BigDecimal(tokensInput))
                    .add(prices[1].multiply(new BigDecimal(tokensOutput)))
                    .divide(new BigDecimal("1000"), 6, RoundingMode.HALF_UP);
        }

        CallLog log = new CallLog();
        log.setUserId(userId);
        log.setApiKeyId(apiKeyId);
        log.setInterfaceId(interfaceId);
        log.setModel(model);
        log.setRequestBody(requestBody);
        log.setResponseBody(responseBody);
        log.setTokensInput(tokensInput);
        log.setTokensOutput(tokensOutput);
        log.setTokensTotal(total);
        log.setCost(cost);
        log.setLatencyMs((int) latencyMs);
        log.setStatus(success ? "success" : "fail");
        log.setErrorMsg(errorMsg);
        save(log);
    }

    @Override
    public Map<String, Object> statsToday(Long userId) {
        LocalDateTime start = LocalDate.now().atStartOfDay();
        LambdaQueryWrapper<CallLog> q = new LambdaQueryWrapper<CallLog>()
                .ge(CallLog::getCreatedAt, start)
                .eq(CallLog::getStatus, "success");
        if (userId != null) {
            q.eq(CallLog::getUserId, userId);
        }
        return aggregate(q);
    }

    @Override
    public List<Map<String, Object>> statsByModel(int days) {
        LocalDateTime start = LocalDate.now().minusDays(days).atStartOfDay();
        List<CallLog> logs = list(new LambdaQueryWrapper<CallLog>()
                .ge(CallLog::getCreatedAt, start)
                .eq(CallLog::getStatus, "success"));

        Map<String, List<CallLog>> grouped = new LinkedHashMap<>();
        for (CallLog log : logs) {
            grouped.computeIfAbsent(log.getModel(), k -> new ArrayList<>()).add(log);
        }

        List<Map<String, Object>> result = new ArrayList<>();
        for (Map.Entry<String, List<CallLog>> entry : grouped.entrySet()) {
            Map<String, Object> item = new LinkedHashMap<>();
            item.put("model", entry.getKey());
            item.put("calls", entry.getValue().size());
            item.put("tokens", entry.getValue().stream().mapToInt(CallLog::getTokensTotal).sum());
            item.put("cost", entry.getValue().stream()
                    .map(CallLog::getCost).reduce(BigDecimal.ZERO, BigDecimal::add));
            result.add(item);
        }
        return result;
    }

    @Override
    public List<Map<String, Object>> statsDaily(int days) {
        List<Map<String, Object>> result = new ArrayList<>();
        for (int i = days - 1; i >= 0; i--) {
            LocalDate date = LocalDate.now().minusDays(i);
            LocalDateTime start = date.atStartOfDay();
            LocalDateTime end = date.atTime(LocalTime.MAX);
            LambdaQueryWrapper<CallLog> q = new LambdaQueryWrapper<CallLog>()
                    .between(CallLog::getCreatedAt, start, end)
                    .eq(CallLog::getStatus, "success");

            Map<String, Object> item = aggregate(q);
            item.put("date", date.toString());
            result.add(item);
        }
        return result;
    }

    private Map<String, Object> aggregate(LambdaQueryWrapper<CallLog> q) {
        List<CallLog> logs = list(q);
        long calls = logs.size();
        long tokens = logs.stream().mapToInt(CallLog::getTokensTotal).sum();
        BigDecimal cost = logs.stream().map(CallLog::getCost).reduce(BigDecimal.ZERO, BigDecimal::add);

        Map<String, Object> data = new LinkedHashMap<>();
        data.put("calls", calls);
        data.put("tokens", tokens);
        data.put("cost", cost);
        return data;
    }
}

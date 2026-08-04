package com.cloudx.biz.service.impl;

import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import com.cloudx.biz.entity.CallLog;
import com.cloudx.biz.mapper.CallLogMapper;
import com.cloudx.biz.service.CallLogService;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.math.RoundingMode;

@Service
@RequiredArgsConstructor
public class CallLogServiceImpl extends ServiceImpl<CallLogMapper, CallLog> implements CallLogService {

    /** 各模型定价：元/千token（简化的，后续可移到 Nacos 配置中心） */
    private static final BigDecimal PRICE_PER_K_TOKEN = new BigDecimal("0.002");

    @Override
    public void record(Long userId, Long apiKeyId, Long interfaceId, String model,
                       String requestBody, String responseBody,
                       int tokensInput, int tokensOutput,
                       long latencyMs, boolean success, String errorMsg) {
        int total = tokensInput + tokensOutput;
        BigDecimal cost = BigDecimal.ZERO;
        if (success && total > 0) {
            cost = PRICE_PER_K_TOKEN.multiply(new BigDecimal(total))
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
}

package com.cloudx.aiagent.service;

import com.cloudx.aiagent.routing.ModelRouter;
import com.cloudx.aiagent.routing.ModelRouter.RouteResult;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.util.Map;

@Slf4j
@Service
@RequiredArgsConstructor
public class ChatService {

    private final ModelRouter modelRouter;
    private final CallLogClient callLogClient;

    public RouteResult chat(String message) {
        return chat(message, null, null);
    }

    public RouteResult chat(String message, String taskType, Long userId) {
        long start = System.currentTimeMillis();
        RouteResult result = modelRouter.route(message, taskType);
        long latency = System.currentTimeMillis() - start;

        // 估算 token 数：中文约 1 token ≈ 2 字符
        int tokensInput = message.length() / 2;
        int tokensOutput = result.reply().length() / 2;

        // 记录调用日志（异步不阻塞，失败不影响主流程）
        callLogClient.record(userId, result.model(), message, result.reply(),
                tokensInput, tokensOutput, latency, true, null);

        return result;
    }

    public Map<String, String> modelStatus() {
        return modelRouter.getProviderStatus();
    }
}

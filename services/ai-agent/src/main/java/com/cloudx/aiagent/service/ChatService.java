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
        return chat(message, null, null, null);
    }

    public RouteResult chat(String message, String taskType, Long userId) {
        return chat(message, null, taskType, userId);
    }

    /**
     * 多轮对话：将历史消息拼入 prompt，让模型感知上下文
     */
    public RouteResult chat(String message, java.util.List<java.util.Map<String, String>> history,
                            String taskType, Long userId) {
        String fullPrompt = buildPrompt(message, history);
        long start = System.currentTimeMillis();
        RouteResult result = modelRouter.route(fullPrompt, taskType);
        long latency = System.currentTimeMillis() - start;

        // 估算 token 数
        int tokensInput = fullPrompt.length() / 2;
        int tokensOutput = result.reply().length() / 2;

        callLogClient.record(userId, result.model(), fullPrompt, result.reply(),
                tokensInput, tokensOutput, latency, true, null);

        return result;
    }

    /**
     * 将历史消息格式化为对话上下文字符串
     */
    private String buildPrompt(String message, java.util.List<java.util.Map<String, String>> history) {
        if (history == null || history.isEmpty()) {
            return message;
        }
        StringBuilder sb = new StringBuilder();
        sb.append("以下是历史对话：\n");
        for (java.util.Map<String, String> msg : history) {
            String role = "user".equals(msg.get("role")) ? "用户" : "AI";
            sb.append(role).append(": ").append(msg.get("content")).append("\n");
        }
        sb.append("\n请根据以上对话上下文，回答用户的当前问题：\n");
        sb.append(message);
        return sb.toString();
    }

    public Map<String, String> modelStatus() {
        return modelRouter.getProviderStatus();
    }
}

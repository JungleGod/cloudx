package com.cloudx.aiagent.agent;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestTemplate;

import java.util.Map;

/**
 * Agent 执行审计日志 — 异步写入 biz-service 的 agent_execution_log 表
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class AgentExecutionLogger {

    private final RestTemplate restTemplate;
    private final String bizServiceUrl = "http://localhost:8081";

    /**
     * 异步记录执行日志（不阻塞 Agent 循环）
     */
    @Async("agentLogExecutor")
    public void log(AgentExecutionContext ctx, AgentResult result) {
        try {
            String url = bizServiceUrl + "/api/internal/agent-executions";
            HttpHeaders headers = new HttpHeaders();
            headers.setContentType(MediaType.APPLICATION_JSON);

            Map<String, Object> body = new java.util.LinkedHashMap<>();
            body.put("agentId", ctx.getAgentId() != null ? ctx.getAgentId() : 0);
            body.put("userId", ctx.getUserId() != null ? ctx.getUserId() : 0);
            body.put("sessionId", "agent-" + System.currentTimeMillis());
            body.put("modelName", ctx.getActualModelName() != null ? ctx.getActualModelName() : "");
            body.put("userMessage", ctx.getUserMessage() != null ? ctx.getUserMessage() : "");
            body.put("finalAnswer", result.getAnswer() != null ? result.getAnswer() : "");
            body.put("iterations", result.getIterations());
            body.put("toolCalls", result.getToolSteps().size());
            body.put("totalTokens", result.getTotalTokens());
            body.put("elapsedMs", result.getElapsedMs());
            body.put("status", result.isInterrupted() ? "interrupted" : "success");

            restTemplate.postForObject(url, new HttpEntity<>(body, headers), String.class);
        } catch (Exception e) {
            log.warn("Failed to log agent execution: {}", e.getMessage());
        }
    }
}

package com.cloudx.aiagent.service;

import com.cloudx.aiagent.agent.AgentMessage;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestTemplate;

import java.time.Duration;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 第三方 Agent 客户端 — OpenAI 兼容契约的 HTTP 任务委托
 *
 * <p>第三方 Agent 是黑盒自主服务：内部有自己的 ReAct 循环和工具，
 * 我们只交接任务（messages）并收回结果（choices[0].message.content + usage）。
 *
 * <p>契约：POST {endpointUrl}，请求/响应均为 OpenAI chat completions 格式
 * （Dify / Coze / OneAPI / 自建网关的事实标准），鉴权用 Bearer endpointKey。
 * 本平台的 /v1/chat/completions 也是同一契约——本平台注册为别家的第三方 Agent 零适配。
 */
@Slf4j
@Component
public class ExternalAgentClient {

    private final ObjectMapper objectMapper;

    public ExternalAgentClient(ObjectMapper objectMapper) {
        this.objectMapper = objectMapper;
    }

    /** 调用结果 */
    public record ExternalResult(String answer, int inputTokens, int outputTokens, String error) {
        public boolean success() {
            return error == null;
        }
    }

    /**
     * 委托任务给第三方 Agent
     *
     * @param target   第三方 Agent 目标（地址、Key、超时）
     * @param messages 完整消息列表（dispatch 场景为单条 task；直连对话场景含历史）
     * @param userId   调用者用户 ID（透传给对方统计用）
     */
    public ExternalResult execute(ExternalAgentTarget target, List<AgentMessage> messages, Long userId) {
        try {
            // 组装 OpenAI 兼容请求体
            List<Map<String, String>> apiMessages = new ArrayList<>();
            for (AgentMessage msg : messages) {
                if ("user".equals(msg.getRole()) || "assistant".equals(msg.getRole())) {
                    apiMessages.add(Map.of("role", msg.getRole(), "content",
                            msg.getContent() != null ? msg.getContent() : ""));
                }
            }
            Map<String, Object> body = new LinkedHashMap<>();
            body.put("model", "agent");
            body.put("messages", apiMessages);
            body.put("stream", false);
            if (userId != null) body.put("user", "cloudx-" + userId);

            HttpHeaders headers = new HttpHeaders();
            headers.setContentType(MediaType.APPLICATION_JSON);
            if (target.endpointKey() != null && !target.endpointKey().isBlank()) {
                headers.setBearerAuth(target.endpointKey());
            }

            // 按 Agent 配置的超时构建一次性 RestTemplate（外部 Agent 内部有自己的循环，耗时长）
            RestTemplate restTemplate = buildRestTemplate(target.timeoutMs());
            long start = System.currentTimeMillis();
            String respBody = restTemplate.postForObject(target.endpointUrl(),
                    new HttpEntity<>(body, headers), String.class);
            long elapsed = System.currentTimeMillis() - start;

            return parseResponse(respBody, elapsed);
        } catch (Exception e) {
            log.warn("External agent [{}] call failed: {}", target.agentName(), e.getMessage());
            return new ExternalResult(null, 0, 0, e.getMessage());
        }
    }

    /** 解析 OpenAI 兼容响应：choices[0].message.content + usage */
    private ExternalResult parseResponse(String respBody, long elapsed) {
        if (respBody == null || respBody.isBlank()) {
            return new ExternalResult(null, 0, 0, "第三方Agent返回空响应");
        }
        try {
            JsonNode root = objectMapper.readTree(respBody);

            // OpenAI 格式错误响应：{"error": {"message": "..."}}
            if (root.has("error")) {
                String msg = root.path("error").path("message").asText("未知错误");
                return new ExternalResult(null, 0, 0, "第三方Agent错误: " + msg);
            }

            JsonNode choice = root.path("choices").path(0);
            String content = choice.path("message").path("content").asText(null);
            if (content == null) {
                return new ExternalResult(null, 0, 0, "第三方Agent响应缺少 choices[0].message.content");
            }

            JsonNode usage = root.path("usage");
            int inputTokens = usage.path("prompt_tokens").asInt(0);
            int outputTokens = usage.path("completion_tokens").asInt(0);

            log.info("External agent [{}] responded in {}ms, tokens={}/{}",
                    "external", elapsed, inputTokens, outputTokens);
            return new ExternalResult(content, inputTokens, outputTokens, null);
        } catch (Exception e) {
            return new ExternalResult(null, 0, 0, "第三方Agent响应解析失败: " + e.getMessage());
        }
    }

    private RestTemplate buildRestTemplate(int timeoutMs) {
        SimpleClientHttpRequestFactory factory = new SimpleClientHttpRequestFactory();
        factory.setConnectTimeout(Duration.ofSeconds(10));
        factory.setReadTimeout(Duration.ofMillis(timeoutMs > 0 ? timeoutMs : 60_000));
        return new RestTemplate(factory);
    }

    /** 第三方 Agent 调度目标 */
    public record ExternalAgentTarget(String agentName, String endpointUrl, String endpointKey, int timeoutMs) {
    }
}
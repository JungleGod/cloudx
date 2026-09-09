package com.cloudx.aiagent.controller;

import com.cloudx.aiagent.agent.*;
import com.cloudx.aiagent.service.AgentService;
import com.cloudx.aiagent.tool.ToolDefinition;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.core.ParameterizedTypeReference;
import org.springframework.http.*;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.client.RestTemplate;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import java.io.IOException;
import java.util.List;
import java.util.Map;

/**
 * Agent API — Agent 执行 + 工具管理
 */
@Slf4j
@RestController
@RequiredArgsConstructor
public class AgentController {

    private final AgentService agentService;
    private final ObjectMapper objectMapper;
    private final RestTemplate restTemplate;
    private static final String BIZ_URL = "http://localhost:8081";

    // ==================== Agent CRUD（代理到 biz-service）====================

    @GetMapping("/api/agents")
    public ResponseEntity<?> listAgents(@RequestParam(required = false) Long userId) {
        try {
            String url = BIZ_URL + "/api/internal/agents" + (userId != null ? "?userId=" + userId : "");
            var resp = restTemplate.exchange(url, HttpMethod.GET, null,
                    new ParameterizedTypeReference<Map<String, Object>>() {});
            return ResponseEntity.ok(resp.getBody());
        } catch (Exception e) {
            return ResponseEntity.status(503).body(Map.of("error", "biz-service 不可用"));
        }
    }

    @PostMapping("/api/agents")
    public ResponseEntity<?> createAgent(@RequestBody Map<String, Object> body) {
        try {
            String url = BIZ_URL + "/api/internal/agents";
            var resp = restTemplate.postForEntity(url, new HttpEntity<>(body), Map.class);
            return ResponseEntity.ok(resp.getBody());
        } catch (Exception e) {
            return ResponseEntity.status(503).body(Map.of("error", "biz-service 不可用"));
        }
    }

    @PutMapping("/api/agents/{id:\\d+}")
    public ResponseEntity<?> updateAgent(@PathVariable Long id, @RequestBody Map<String, Object> body) {
        try {
            String url = BIZ_URL + "/api/internal/agents/" + id;
            var resp = restTemplate.exchange(url, HttpMethod.PUT,
                    new HttpEntity<>(body), Map.class);
            return ResponseEntity.ok(resp.getBody());
        } catch (Exception e) {
            return ResponseEntity.status(503).body(Map.of("error", "biz-service 不可用"));
        }
    }

    @DeleteMapping("/api/agents/{id:\\d+}")
    public ResponseEntity<?> deleteAgent(@PathVariable Long id) {
        try {
            String url = BIZ_URL + "/api/internal/agents/" + id;
            restTemplate.delete(url);
            return ResponseEntity.ok(Map.of("message", "ok"));
        } catch (Exception e) {
            return ResponseEntity.status(503).body(Map.of("error", "biz-service 不可用"));
        }
    }

    @GetMapping("/api/agents/{id:\\d+}")
    public ResponseEntity<?> getAgent(@PathVariable Long id) {
        try {
            String url = BIZ_URL + "/api/internal/agents/" + id;
            var resp = restTemplate.getForEntity(url, Map.class);
            return ResponseEntity.ok(resp.getBody());
        } catch (Exception e) {
            return ResponseEntity.status(503).body(Map.of("error", "biz-service 不可用"));
        }
    }

    // ==================== Agent 执行 ====================

    /**
     * 同步执行 Agent（带工具调用）
     */
    @PostMapping("/api/agents/execute")
    public ResponseEntity<?> executeAgent(@RequestBody AgentExecuteRequest request) {
        AgentExecutionContext ctx = buildContext(request);
        AgentResult result = agentService.execute(ctx);
        // 统一返回体 {code, msg, data}，与前端 axios 拦截器约定一致
        return ResponseEntity.ok(Map.of(
                "code", 200,
                "msg", "success",
                "data", toResponseMap(result)
        ));
    }

    /**
     * 流式执行 Agent（SSE）— 支持 tool_call 事件
     */
    @PostMapping(value = "/api/agents/execute/stream", produces = MediaType.TEXT_EVENT_STREAM_VALUE)
    public SseEmitter executeAgentStream(@RequestBody AgentExecuteRequest request) {
        SseEmitter emitter = new SseEmitter(300_000L);
        AgentExecutionContext ctx = buildContext(request);

        AgentStreamCallback callback = new AgentStreamCallback() {
            @Override
            public void onThinking() {
                sendEvent("thinking", Map.of("message", "Agent 正在思考..."));
            }

            @Override
            public void onToolCallStart(String toolName, String callId) {
                sendEvent("tool_call_start", Map.of(
                        "toolName", toolName,
                        "callId", callId
                ));
            }

            @Override
            public void onToolCallArgs(String callId, String delta) {
                sendEvent("tool_call_args", Map.of(
                        "callId", callId,
                        "delta", delta
                ));
            }

            @Override
            public void onToolCallExecuting(String toolName, String arguments) {
                sendEvent("tool_call_executing", Map.of(
                        "toolName", toolName,
                        "arguments", arguments
                ));
            }

            @Override
            public void onToolResult(String toolName, String result, boolean success, long elapsedMs) {
                sendEvent("tool_result", Map.of(
                        "toolName", toolName,
                        "result", result,
                        "success", success,
                        "elapsedMs", elapsedMs
                ));
            }

            @Override
            public void onToken(String token) {
                sendEvent("token", Map.of("token", token));
            }

            @Override
            public void onDone(String fullContent) {
                sendEvent("done", Map.of("content", fullContent != null ? fullContent : ""));
                emitter.complete();
            }

            @Override
            public void onError(Throwable error) {
                sendEvent("error", Map.of("message", error.getMessage()));
                emitter.completeWithError(error);
            }

            private void sendEvent(String event, Object data) {
                try {
                    emitter.send(SseEmitter.event()
                            .name(event)
                            .data(data, MediaType.APPLICATION_JSON));
                } catch (IOException e) {
                    log.debug("SSE send failed: {}", e.getMessage());
                }
            }
        };

        new Thread(() -> agentService.executeStream(ctx, callback)).start();
        return emitter;
    }

    // ==================== 工具管理 ====================

    /** 获取 Agent 绑定的工具列表 */
    @GetMapping("/api/agents/{agentId:\\d+}/tools")
    public ResponseEntity<?> getAgentTools(
            @PathVariable Long agentId,
            @RequestParam(defaultValue = "user") String role) {
        List<ToolDefinition> tools = agentService.getAgentTools(agentId, role);
        return ResponseEntity.ok(Map.of(
                "code", 200,
                "msg", "success",
                "data", Map.of("tools", tools, "count", tools.size())
        ));
    }

    /** 获取所有可用工具 */
    @GetMapping("/api/admin/tools")
    public ResponseEntity<?> getAllTools(
            @RequestParam(defaultValue = "admin") String role) {
        List<ToolDefinition> tools = agentService.getAvailableTools(role);
        return ResponseEntity.ok(Map.of(
                "code", 200,
                "msg", "success",
                "data", Map.of("tools", tools, "count", tools.size())
        ));
    }

    /** 热重载工具注册表 */
    @PostMapping("/api/admin/tools/reload")
    public ResponseEntity<?> reloadTools() {
        agentService.reloadTools();
        return ResponseEntity.ok(Map.of("message", "工具注册表已重新加载"));
    }

    // ==================== 辅助方法 ====================

    private AgentExecutionContext buildContext(AgentExecuteRequest req) {
        // 解析历史消息
        List<AgentMessage> history = null;
        if (req.getHistory() != null && !req.getHistory().isEmpty()) {
            history = req.getHistory().stream()
                    .map(h -> AgentMessage.builder()
                            .role(h.get("role"))
                            .content(h.get("content"))
                            .build())
                    .toList();
        }

        return AgentExecutionContext.builder()
                .userId(req.getUserId())
                .callerRole(req.getRole() != null ? req.getRole() : "user")
                .agentId(req.getAgentId())
                .agentName(req.getAgentName())
                .systemPrompt(req.getSystemPrompt())
                .userMessage(req.getMessage())
                .history(history)
                .modelName(req.getModel())
                .temperature(req.getTemperature())
                .maxIterations(req.getMaxIterations())
                .stream(req.isStream())
                .build();
    }

    private Map<String, Object> toResponseMap(AgentResult result) {
        return Map.of(
                "answer", result.getAnswer() != null ? result.getAnswer() : "",
                "iterations", result.getIterations(),
                "toolSteps", result.getToolSteps().stream().map(s -> Map.of(
                        "iteration", s.getIteration(),
                        "toolName", s.getToolName(),
                        "arguments", s.getArguments(),
                        "result", s.getResult(),
                        "success", s.isSuccess(),
                        "elapsedMs", s.getElapsedMs()
                )).toList(),
                "totalTokens", result.getTotalTokens(),
                "elapsedMs", result.getElapsedMs(),
                "interrupted", result.isInterrupted()
        );
    }

    // ==================== 请求 DTO ====================

    @lombok.Data
    public static class AgentExecuteRequest {
        private Long userId;
        private String role;
        private Long agentId;
        private String agentName;
        private String systemPrompt;
        private String message;
        private String model;
        private Double temperature;
        private Integer maxIterations;
        private boolean stream;
        private List<Map<String, String>> history;
    }
}

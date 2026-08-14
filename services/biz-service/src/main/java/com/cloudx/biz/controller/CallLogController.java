package com.cloudx.biz.controller;

import com.cloudx.biz.entity.*;
import com.cloudx.biz.mapper.SysUserMapper;
import com.cloudx.biz.service.*;
import com.cloudx.common.result.R;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.web.bind.annotation.*;

import java.util.*;
import java.util.stream.Collectors;

@Slf4j
@RestController
@RequiredArgsConstructor
public class CallLogController {

    private final CallLogService callLogService;
    private final ApiKeyService apiKeyService;
    private final AgentDefinitionService agentDefService;
    private final ToolDefinitionService toolDefService;
    private final AgentExecutionLogService execLogService;
    private final SysUserMapper sysUserMapper;

    // ==================== 内部统计 API ====================

    /** 内部接口：系统实时统计 */
    @GetMapping("/api/internal/stats")
    public R<Map<String, Object>> systemStats() {
        long userCount = sysUserMapper.selectCount(null);
        long apiKeyCount = apiKeyService.count();
        return R.ok(Map.of(
                "userCount", userCount,
                "timestamp", System.currentTimeMillis()
        ));
    }

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

    // ==================== Agent 内部 API ====================

    /** 内部接口：获取 Agent 定义 */
    @GetMapping("/api/internal/agents/{id}")
    public R<Map<String, Object>> getAgent(@PathVariable Long id) {
        AgentDefinition agent = agentDefService.getById(id);
        if (agent == null) return R.fail("Agent 不存在");
        List<ToolDefinition> tools = agentDefService.getBoundTools(id);
        return R.ok(Map.of(
                "id", agent.getId(),
                "name", agent.getName(),
                "systemPrompt", agent.getSystemPrompt(),
                "model", agent.getModel() != null ? agent.getModel() : "",
                "temperature", agent.getTemperature(),
                "maxTokens", agent.getMaxTokens(),
                "maxIterations", agent.getMaxIterations(),
                "status", agent.getStatus(),
                "tools", tools
        ));
    }

    /** 内部接口：获取 Agent 绑定的工具 */
    @GetMapping("/api/internal/agents/{id}/tools")
    public R<List<ToolDefinition>> getAgentTools(@PathVariable Long id) {
        return R.ok(agentDefService.getBoundTools(id));
    }

    /** 内部接口：批量按名称获取工具 */
    @PostMapping("/api/internal/tools/batch")
    public R<List<ToolDefinition>> getToolsByNames(@RequestBody Map<String, List<String>> body) {
        List<String> names = body.getOrDefault("names", List.of());
        return R.ok(toolDefService.getByNames(names));
    }

    /** 内部接口：列出所有 Agent */
    @GetMapping("/api/internal/agents")
    public R<List<AgentDefinition>> listAgents(@RequestParam(required = false) Long userId) {
        if (userId != null) {
            return R.ok(agentDefService.listByUser(userId));
        }
        return R.ok(agentDefService.list());
    }

    /** 内部接口：创建 Agent */
    @PostMapping("/api/internal/agents")
    public R<AgentDefinition> createAgent(@RequestBody AgentDefinition agent) {
        agentDefService.create(agent);
        return R.ok(agent);
    }

    /** 内部接口：更新 Agent */
    @PutMapping("/api/internal/agents/{id}")
    public R<Void> updateAgent(@PathVariable Long id, @RequestBody AgentDefinition agent) {
        agentDefService.updateAgent(id, agent);
        return R.ok();
    }

    /** 内部接口：删除 Agent */
    @DeleteMapping("/api/internal/agents/{id}")
    public R<Void> deleteAgent(@PathVariable Long id) {
        agentDefService.removeById(id);
        return R.ok();
    }

    /** 内部接口：记录 Agent 执行日志 */
    @PostMapping("/api/internal/agent-executions")
    public R<Void> recordExecution(@RequestBody Map<String, Object> body) {
        AgentExecutionLog logEntry = new AgentExecutionLog();
        logEntry.setAgentId(toLong(body.get("agentId")));
        logEntry.setUserId(toLong(body.get("userId")));
        logEntry.setApiKeyId(toLong(body.get("apiKeyId")));
        logEntry.setSessionId((String) body.get("sessionId"));
        logEntry.setIteration(toInt(body.get("iteration")));
        logEntry.setStepType((String) body.get("stepType"));
        logEntry.setModel((String) body.get("model"));
        logEntry.setToolName((String) body.get("toolName"));
        logEntry.setRequestBody((String) body.get("requestBody"));
        logEntry.setResponseBody((String) body.get("responseBody"));
        logEntry.setTokensInput(toInt(body.get("tokensInput")));
        logEntry.setTokensOutput(toInt(body.get("tokensOutput")));
        logEntry.setLatencyMs(toInt(body.get("latencyMs")));
        logEntry.setStatus((String) body.getOrDefault("status", "success"));
        logEntry.setErrorMsg((String) body.get("errorMsg"));
        execLogService.save(logEntry);
        return R.ok();
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

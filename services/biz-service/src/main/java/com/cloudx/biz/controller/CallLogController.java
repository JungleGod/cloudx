package com.cloudx.biz.controller;

import com.cloudx.biz.entity.*;
import com.cloudx.biz.mapper.SysUserMapper;
import com.cloudx.biz.service.*;
import com.cloudx.biz.util.JwtUtil;
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
    private final QuotaService quotaService;
    private final SysUserMapper sysUserMapper;
    private final JwtUtil jwtUtil;

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
        String requestedModel = (String) body.getOrDefault("requestedModel", "");
        String requestBody = (String) body.getOrDefault("requestBody", "");
        String responseBody = (String) body.getOrDefault("responseBody", "");
        int tokensInput = toInt(body.get("tokensInput"));
        int tokensOutput = toInt(body.get("tokensOutput"));
        long latencyMs = toLong(body.get("latencyMs"));
        boolean success = "success".equals(body.get("status"));
        String errorMsg = (String) body.getOrDefault("errorMsg", "");

        // apiKeyId/interfaceId 传 0：在线调试等场景没有 API Key，用 0 表示非 Key 调用
        callLogService.record(userId, 0L, null, model, requestedModel,
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

    /** 内部接口：额度校验（被 ai-agent 调用，调用模型前拦截超额度请求） */
    @PostMapping("/api/internal/quota/check")
    public R<Map<String, Object>> checkQuota(@RequestBody Map<String, Object> body) {
        Long userId = toLong(body.get("userId"));
        return R.ok(toQuotaMap(quotaService.check(userId)));
    }

    /** 用户本月额度状态（前端概览展示；admin 可传 userId 查看他人） */
    @GetMapping("/api/stats/quota")
    public R<Map<String, Object>> quota(@RequestHeader(value = "Authorization", required = false) String authHeader,
                                        @RequestParam(required = false) Long userId) {
        return R.ok(toQuotaMap(quotaService.check(resolveQuotaUserId(authHeader, userId))));
    }

    // ==================== Agent 内部 API ====================

    /** 内部接口：获取 Agent 定义 */
    @GetMapping("/api/internal/agents/{id}")
    public R<Map<String, Object>> getAgent(@PathVariable Long id) {
        AgentDefinition agent = agentDefService.getById(id);
        if (agent == null) return R.fail("Agent 不存在");
        List<ToolDefinition> tools = agentDefService.getBoundTools(id);
        Map<String, Object> data = new java.util.LinkedHashMap<>();
        data.put("id", agent.getId());
        data.put("name", agent.getName());
        data.put("systemPrompt", agent.getSystemPrompt());
        data.put("model", agent.getModel() != null ? agent.getModel() : "");
        data.put("temperature", agent.getTemperature());
        data.put("maxTokens", agent.getMaxTokens());
        data.put("maxIterations", agent.getMaxIterations());
        data.put("executionType", agent.getExecutionType() != null ? agent.getExecutionType() : "internal");
        data.put("endpointUrl", agent.getEndpointUrl() != null ? agent.getEndpointUrl() : "");
        data.put("dispatchTimeoutMs", agent.getDispatchTimeoutMs() != null ? agent.getDispatchTimeoutMs() : 60000);
        data.put("status", agent.getStatus());
        data.put("tools", tools);
        return R.ok(data);
    }

    /** 内部接口：第三方 Agent 调度信息（下发明文 Key，仅 ai-agent 内网拉取） */
    @GetMapping("/api/internal/agents/{id}/dispatch-info")
    public R<Map<String, Object>> getDispatchInfo(@PathVariable Long id) {
        AgentDefinition agent = agentDefService.getById(id);
        if (agent == null) return R.fail("Agent 不存在");
        if (!"external".equals(agent.getExecutionType())) {
            return R.fail("非第三方 Agent");
        }
        return R.ok(Map.of(
                "id", agent.getId(),
                "name", agent.getName(),
                "endpointUrl", agent.getEndpointUrl() != null ? agent.getEndpointUrl() : "",
                "endpointKey", agentDefService.decryptEndpointKey(agent.getEndpointKey()) != null
                        ? agentDefService.decryptEndpointKey(agent.getEndpointKey()) : "",
                "dispatchTimeoutMs", agent.getDispatchTimeoutMs() != null ? agent.getDispatchTimeoutMs() : 60000
        ));
    }

    /** 内部接口：获取 Agent 绑定的工具 */
    @GetMapping("/api/internal/agents/{id}/tools")
    public R<List<ToolDefinition>> getAgentTools(@PathVariable Long id) {
        return R.ok(agentDefService.getBoundTools(id));
    }

    /** 内部接口：绑定 Agent 的工具（全量覆盖） */
    @PutMapping("/api/internal/agents/{id}/tools")
    public R<Void> bindAgentTools(@PathVariable Long id, @RequestBody Map<String, List<Long>> body) {
        agentDefService.bindTools(id, body.getOrDefault("toolIds", List.of()));
        return R.ok();
    }

    /** 内部接口：批量按名称获取工具 */
    @PostMapping("/api/internal/tools/batch")
    public R<List<ToolDefinition>> getToolsByNames(@RequestBody Map<String, List<String>> body) {
        List<String> names = body.getOrDefault("names", List.of());
        return R.ok(toolDefService.getByNames(names));
    }

    /** 内部接口：列出全部工具（含停用，管理页用） */
    @GetMapping("/api/internal/tools")
    public R<List<ToolDefinition>> listAllTools() {
        return R.ok(toolDefService.list());
    }

    /** 内部接口：创建工具（built-in 类别拒绝） */
    @PostMapping("/api/internal/tools")
    public R<Void> createTool(@RequestBody ToolDefinition tool) {
        toolDefService.createTool(tool);
        return R.ok();
    }

    /** 内部接口：更新工具 */
    @PutMapping("/api/internal/tools/{id}")
    public R<Void> updateTool(@PathVariable Long id, @RequestBody ToolDefinition tool) {
        toolDefService.updateTool(id, tool);
        return R.ok();
    }

    /** 内部接口：删除工具（级联解除 Agent 绑定） */
    @DeleteMapping("/api/internal/tools/{id}")
    public R<Void> deleteTool(@PathVariable Long id) {
        toolDefService.deleteTool(id);
        return R.ok();
    }

    /** 内部接口：列出所有 Agent（endpointKey 不下发，明文仅经 /dispatch-info 内网下发） */
    @GetMapping("/api/internal/agents")
    public R<List<AgentDefinition>> listAgents(@RequestParam(required = false) Long userId) {
        List<AgentDefinition> agents = userId != null
                ? agentDefService.listByUser(userId) : agentDefService.list();
        agents.forEach(a -> a.setEndpointKey(null));
        return R.ok(agents);
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
    public R<Map<String, Object>> today(@RequestHeader(value = "Authorization", required = false) String authHeader,
                                        @RequestParam(required = false) Long userId) {
        return R.ok(callLogService.statsToday(resolveStatsUserId(authHeader, userId)));
    }

    /** 按模型统计 */
    @GetMapping("/api/stats/by-model")
    public R<List<Map<String, Object>>> byModel(@RequestHeader(value = "Authorization", required = false) String authHeader,
                                                @RequestParam(required = false) Long userId,
                                                @RequestParam(defaultValue = "7") int days) {
        return R.ok(callLogService.statsByModel(days, resolveStatsUserId(authHeader, userId)));
    }

    /** 每日统计 */
    @GetMapping("/api/stats/daily")
    public R<List<Map<String, Object>>> daily(@RequestHeader(value = "Authorization", required = false) String authHeader,
                                              @RequestParam(required = false) Long userId,
                                              @RequestParam(defaultValue = "30") int days) {
        return R.ok(callLogService.statsDaily(days, resolveStatsUserId(authHeader, userId)));
    }

    /**
     * 解析统计接口的用户过滤条件：
     * - 前端经 gateway 带 JWT：admin 返回 null（看全平台），普通用户返回自己的 userId
     * - 内部服务直连（ai-agent → biz-service，无 JWT）：沿用显式 userId 参数（null = 全局）
     */
    private Long resolveStatsUserId(String authHeader, Long userId) {
        if (authHeader != null && !authHeader.isBlank()) {
            String token = authHeader.replace("Bearer ", "");
            if ("admin".equals(jwtUtil.getRole(token))) {
                return null;
            }
            return jwtUtil.getUserId(token);
        }
        return userId;
    }

    /**
     * 解析额度查询的目标用户：
     * - 普通用户：永远看自己（忽略 userId 参数）
     * - admin：默认看自己（不限），传 userId 可查看指定用户
     * - 内部直连（无 JWT）：沿用显式 userId
     */
    private Long resolveQuotaUserId(String authHeader, Long userId) {
        if (authHeader != null && !authHeader.isBlank()) {
            String token = authHeader.replace("Bearer ", "");
            if ("admin".equals(jwtUtil.getRole(token)) && userId != null) {
                return userId;
            }
            return jwtUtil.getUserId(token);
        }
        return userId;
    }

    private Map<String, Object> toQuotaMap(QuotaService.QuotaStatus s) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("unlimited", s.unlimited());
        m.put("month", s.month());
        m.put("quota", s.quota());
        m.put("used", s.used());
        m.put("remaining", s.remaining());
        m.put("exceeded", s.exceeded());
        return m;
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

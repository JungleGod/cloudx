package com.cloudx.biz.controller;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import com.baomidou.mybatisplus.core.metadata.IPage;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.cloudx.biz.entity.AgentDefinition;
import com.cloudx.biz.entity.AgentExecutionLog;
import com.cloudx.biz.entity.SysUser;
import com.cloudx.biz.mapper.AgentDefinitionMapper;
import com.cloudx.biz.mapper.AgentExecutionLogMapper;
import com.cloudx.biz.mapper.SysUserMapper;
import com.cloudx.common.exception.BizException;
import com.cloudx.common.result.R;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.*;

import java.util.*;
import java.util.stream.Collectors;

/**
 * 管理员接口 — Agent 执行审计查询
 * agent_execution_log 是步骤级明细（llm_call/tool_call/tool_result/final_answer），
 * 列表按 session_id 聚合（一次 Agent 执行一行），详情返回该次执行的完整步骤链，
 * 配合 Agent-as-Tool 编排可直观看到 L0 派发子 Agent 的过程。
 * Gateway AuthFilter 已校验 JWT 并透传 X-User-Role 请求头
 */
@RestController
@RequestMapping("/api/admin/agent-logs")
@RequiredArgsConstructor
public class AdminAgentLogController {

    /** 详情大字段截断长度（LLM 请求体可能几十 KB，审计页不需要全量） */
    private static final int PREVIEW_LEN = 2000;

    private final AgentExecutionLogMapper agentExecutionLogMapper;
    private final AgentDefinitionMapper agentDefinitionMapper;
    private final SysUserMapper sysUserMapper;

    /** 校验是否管理员，不是则直接拒绝 */
    private void requireAdmin(String role) {
        if (!"admin".equals(role)) {
            throw new BizException(403, "无权限，仅管理员可操作");
        }
    }

    /** 分页查询：按执行会话聚合 */
    @GetMapping
    public R<Map<String, Object>> list(@RequestHeader("X-User-Role") String role,
                                       @RequestParam(defaultValue = "1") int page,
                                       @RequestParam(defaultValue = "20") int size,
                                       @RequestParam(required = false) Long userId,
                                       @RequestParam(required = false) Long agentId,
                                       @RequestParam(required = false) String start,
                                       @RequestParam(required = false) String end) {
        requireAdmin(role);
        QueryWrapper<AgentExecutionLog> qw = new QueryWrapper<AgentExecutionLog>()
                .eq(userId != null, "user_id", userId)
                .eq(agentId != null, "agent_id", agentId)
                .ge(start != null && !start.isBlank(), "created_at", start)
                .le(end != null && !end.isBlank(), "created_at", end)
                .groupBy("session_id")
                .orderByDesc("MIN(created_at)");
        IPage<Map<String, Object>> result =
                agentExecutionLogMapper.selectSessionPage(new Page<>(page, Math.min(size, 100)), qw);

        // 批量补 username / agentName，避免 N+1
        Set<Long> userIds = new HashSet<>();
        Set<Long> agentIds = new HashSet<>();
        for (Map<String, Object> row : result.getRecords()) {
            addId(userIds, row.get("user_id"));
            addId(agentIds, row.get("agent_id"));
        }
        Map<Long, String> nameMap = userIds.isEmpty() ? Map.of()
                : sysUserMapper.selectBatchIds(userIds).stream()
                        .collect(Collectors.toMap(SysUser::getId, SysUser::getUsername));
        Map<Long, String> agentMap = agentIds.isEmpty() ? Map.of()
                : agentDefinitionMapper.selectBatchIds(agentIds).stream()
                        .collect(Collectors.toMap(AgentDefinition::getId, AgentDefinition::getName));

        List<Map<String, Object>> records = result.getRecords().stream()
                .map(row -> toSessionItem(row, nameMap, agentMap))
                .collect(Collectors.toList());

        Map<String, Object> data = new LinkedHashMap<>();
        data.put("records", records);
        data.put("total", result.getTotal());
        return R.ok(data);
    }

    /** 单次执行的完整步骤链（按写入顺序） */
    @GetMapping("/{sessionId}")
    public R<Map<String, Object>> detail(@RequestHeader("X-User-Role") String role,
                                         @PathVariable String sessionId) {
        requireAdmin(role);
        List<AgentExecutionLog> steps = agentExecutionLogMapper.selectList(
                new LambdaQueryWrapper<AgentExecutionLog>()
                        .eq(AgentExecutionLog::getSessionId, sessionId)
                        .orderByAsc(AgentExecutionLog::getId));
        if (steps.isEmpty()) return R.fail("执行记录不存在");

        Map<String, Object> data = new LinkedHashMap<>();
        data.put("sessionId", sessionId);
        data.put("agentId", steps.get(0).getAgentId());
        data.put("agentName", resolveAgentName(steps.get(0).getAgentId()));
        data.put("userId", steps.get(0).getUserId());
        data.put("username", resolveUsername(steps.get(0).getUserId()));
        data.put("steps", steps.stream().map(this::toStepItem).collect(Collectors.toList()));
        return R.ok(data);
    }

    // ---------- 组装 ----------

    private Map<String, Object> toSessionItem(Map<String, Object> row,
                                              Map<Long, String> nameMap, Map<Long, String> agentMap) {
        long userId = toLong(row.get("user_id"));
        long agentId = toLong(row.get("agent_id"));
        long errorSteps = toLong(row.get("error_steps"));
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("sessionId", row.get("session_id"));
        m.put("userId", userId);
        m.put("username", nameMap.getOrDefault(userId, "用户#" + userId + "(已删除)"));
        m.put("agentId", agentId);
        m.put("agentName", agentMap.getOrDefault(agentId, "Agent#" + agentId + "(已删除)"));
        m.put("steps", toLong(row.get("steps")));
        m.put("tokensInput", toLong(row.get("tokens_input")));
        m.put("tokensOutput", toLong(row.get("tokens_output")));
        m.put("errorSteps", errorSteps);
        m.put("status", errorSteps > 0 ? "error" : "success");
        m.put("startedAt", String.valueOf(row.get("started_at")));
        m.put("endedAt", String.valueOf(row.get("ended_at")));
        return m;
    }

    private Map<String, Object> toStepItem(AgentExecutionLog s) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("id", s.getId());
        m.put("iteration", s.getIteration());
        m.put("stepType", s.getStepType());
        m.put("model", s.getModel());
        m.put("toolName", s.getToolName());
        m.put("tokensInput", s.getTokensInput());
        m.put("tokensOutput", s.getTokensOutput());
        m.put("latencyMs", s.getLatencyMs());
        m.put("status", s.getStatus());
        m.put("errorMsg", s.getErrorMsg());
        m.put("createdAt", s.getCreatedAt() == null ? null : s.getCreatedAt().toString());
        m.put("requestPreview", preview(s.getRequestBody()));
        m.put("responsePreview", preview(s.getResponseBody()));
        return m;
    }

    private String resolveUsername(Long userId) {
        if (userId == null) return "-";
        SysUser user = sysUserMapper.selectById(userId);
        return user != null ? user.getUsername() : "用户#" + userId + "(已删除)";
    }

    private String resolveAgentName(Long agentId) {
        if (agentId == null) return "-";
        AgentDefinition agent = agentDefinitionMapper.selectById(agentId);
        return agent != null ? agent.getName() : "Agent#" + agentId + "(已删除)";
    }

    private void addId(Set<Long> ids, Object v) {
        if (v instanceof Number && ((Number) v).longValue() > 0) {
            ids.add(((Number) v).longValue());
        }
    }

    private long toLong(Object v) {
        return v == null ? 0 : ((Number) v).longValue();
    }

    private String preview(String text) {
        if (text == null) return "";
        return text.length() <= PREVIEW_LEN ? text : text.substring(0, PREVIEW_LEN) + "...(截断)";
    }
}
package com.cloudx.aiagent.service;

import com.cloudx.aiagent.agent.*;
import com.cloudx.aiagent.tool.ToolDefinition;
import com.cloudx.aiagent.tool.ToolRegistry;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

/**
 * Agent 编排服务 — 负责 Agent 执行、CRUD 代理、工具管理
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class AgentService {

    private final AgentLoop agentLoop;
    private final ToolRegistry toolRegistry;
    private final AgentExecutionLogger executionLogger;
    private final CallLogClient callLogClient;

    /**
     * 同步执行 Agent
     */
    public AgentResult execute(AgentExecutionContext ctx) {
        log.info("Agent [{}] sync execution for user {}", ctx.getAgentName(), ctx.getUserId());
        return agentLoop.execute(ctx, null);
    }

    /**
     * 流式执行 Agent（SSE 回调）
     */
    public void executeStream(AgentExecutionContext ctx, AgentStreamCallback callback) {
        log.info("Agent [{}] stream execution for user {}", ctx.getAgentName(), ctx.getUserId());

        // 包装回调：记录调用日志
        AgentStreamCallback wrapped = new AgentStreamCallback() {
            private final StringBuilder fullContent = new StringBuilder();
            private final long start = System.currentTimeMillis();

            @Override
            public void onThinking() { callback.onThinking(); }

            @Override
            public void onToolCallStart(String toolName, String callId) {
                callback.onToolCallStart(toolName, callId);
            }

            @Override
            public void onToolCallArgs(String callId, String delta) {
                callback.onToolCallArgs(callId, delta);
            }

            @Override
            public void onToolCallExecuting(String toolName, String arguments) {
                callback.onToolCallExecuting(toolName, arguments);
            }

            @Override
            public void onToolResult(String toolName, String result, boolean success, long elapsedMs) {
                callback.onToolResult(toolName, result, success, elapsedMs);
            }

            @Override
            public void onToken(String token) {
                fullContent.append(token);
                callback.onToken(token);
            }

            @Override
            public void onDone(String fullReply) {
                if (fullReply != null) fullContent.append(fullReply);
                long latency = System.currentTimeMillis() - start;
                callLogClient.record(ctx.getUserId(), ctx.getActualModelName() != null ? ctx.getActualModelName() : "agent",
                        ctx.getUserMessage(), fullContent.toString(),
                        ctx.getUserMessage().length() / 2, fullContent.length() / 2,
                        latency, true, null);
                callback.onDone(fullReply);
            }

            @Override
            public void onError(Throwable error) {
                long latency = System.currentTimeMillis() - start;
                callLogClient.record(ctx.getUserId(), ctx.getActualModelName() != null ? ctx.getActualModelName() : "agent",
                        ctx.getUserMessage(), fullContent.toString(),
                        ctx.getUserMessage().length() / 2, fullContent.length() / 2,
                        latency, false, error.getMessage());
                callback.onError(error);
            }
        };

        agentLoop.executeStream(ctx, wrapped);
    }

    // ==================== 工具查询 ====================

    /** 获取所有可用的工具（按角色过滤） */
    public List<ToolDefinition> getAvailableTools(String callerRole) {
        return toolRegistry.getToolsByNames(
                toolRegistry.getAll().stream().map(ToolDefinition::getName).collect(Collectors.toList()),
                callerRole);
    }

    /** 获取 Agent 绑定的工具 */
    public List<ToolDefinition> getAgentTools(Long agentId, String callerRole) {
        return toolRegistry.getToolsForAgent(agentId, callerRole);
    }

    /** 热重载工具注册表 */
    public void reloadTools() {
        toolRegistry.reload();
        log.info("Tool registry reloaded");
    }
}

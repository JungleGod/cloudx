package com.cloudx.aiagent.service;

import com.cloudx.aiagent.agent.*;
import com.cloudx.aiagent.tool.ToolDefinition;
import com.cloudx.aiagent.tool.ToolRegistry;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
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
    private final AgentCatalog agentCatalog;
    private final AgentExecutionLogger executionLogger;
    private final CallLogClient callLogClient;
    private final ExternalAgentClient externalAgentClient;

    /**
     * 同步执行 Agent（external 类型第三方 Agent 直接 HTTP 委托）
     */
    public AgentResult execute(AgentExecutionContext ctx) {
        log.info("Agent [{}] sync execution for user {}", ctx.getAgentName(), ctx.getUserId());

        // 第三方 Agent 直连：不走本地 AgentLoop
        ExternalAgentClient.ExternalAgentTarget target = resolveExternalTarget(ctx);
        if (target != null) {
            return executeExternal(ctx, target);
        }

        AgentResult result = agentLoop.execute(ctx, null);
        recordSyncCallLog(ctx, result, true, null);
        return result;
    }

    /**
     * 流式执行 Agent（SSE 回调）
     */
    public void executeStream(AgentExecutionContext ctx, AgentStreamCallback callback) {
        log.info("Agent [{}] stream execution for user {}", ctx.getAgentName(), ctx.getUserId());

        AgentStreamCallback wrapped = buildLoggingCallback(ctx, callback);

        // 第三方 Agent 直连：HTTP 委托，结果以整体 token 回放给前端
        ExternalAgentClient.ExternalAgentTarget target = resolveExternalTarget(ctx);
        if (target != null) {
            streamExternal(ctx, target, wrapped);
            return;
        }

        agentLoop.executeStream(ctx, wrapped);
    }

    // ==================== 第三方 Agent（external）====================

    /** 判断目标 Agent 是否第三方（external），是则返回调度目标 */
    private ExternalAgentClient.ExternalAgentTarget resolveExternalTarget(AgentExecutionContext ctx) {
        Map<String, Object> def = agentCatalog.getById(ctx.getAgentId());
        if (def == null && ctx.getAgentName() != null) {
            def = agentCatalog.getByName(ctx.getAgentName());
        }
        if (def == null || !"external".equals(def.get("executionType"))) {
            return null;
        }
        Object id = def.get("id");
        Long agentId = id instanceof Number n ? n.longValue() : null;
        return agentCatalog.getExternalTarget(agentId);
    }

    /** 第三方 Agent 同步执行：HTTP 委托 */
    private AgentResult executeExternal(AgentExecutionContext ctx, ExternalAgentClient.ExternalAgentTarget target) {
        long start = System.currentTimeMillis();
        ExternalAgentClient.ExternalResult ext = callExternal(ctx, target);

        AgentResult result = AgentResult.builder()
                .iterations(1)
                .interrupted(false)
                .inputTokens(ext.inputTokens())
                .outputTokens(ext.outputTokens())
                .totalTokens(ext.inputTokens() + ext.outputTokens())
                .build();
        result.setAnswer(ext.success() ? ext.answer() : "第三方Agent调用失败: " + ext.error());
        result.setElapsedMs(System.currentTimeMillis() - start);
        executionLogger.log(ctx, result);

        if (ext.success()) {
            recordSyncCallLog(ctx, result, true, null);
        } else {
            recordSyncCallLog(ctx, result, false, ext.error());
        }
        return result;
    }

    /** 第三方 Agent 流式执行：结果以整体 token 回放（对方不支持我们的 SSE 透传） */
    private void streamExternal(AgentExecutionContext ctx, ExternalAgentClient.ExternalAgentTarget target,
                                AgentStreamCallback wrapped) {
        try {
            ExternalAgentClient.ExternalResult ext = callExternal(ctx, target);
            if (ext.success()) {
                wrapped.onThinking();
                if (!ext.answer().isEmpty()) {
                    wrapped.onToken(ext.answer());
                }
                wrapped.onUsage(ext.inputTokens(), ext.outputTokens());
                wrapped.onDone(ext.answer());
            } else {
                wrapped.onError(new RuntimeException(ext.error()));
            }
        } catch (Exception e) {
            log.error("External agent [{}] stream failed: {}", target.agentName(), e.getMessage());
            wrapped.onError(e);
        }
    }

    /** 组装消息并调用第三方 Agent（直连场景带历史上下文） */
    private ExternalAgentClient.ExternalResult callExternal(AgentExecutionContext ctx,
                                                            ExternalAgentClient.ExternalAgentTarget target) {
        List<AgentMessage> messages = new ArrayList<>();
        if (ctx.getHistory() != null) {
            messages.addAll(ctx.getHistory());
        }
        messages.add(AgentMessage.user(ctx.getUserMessage() != null ? ctx.getUserMessage() : ""));
        return externalAgentClient.execute(target, messages, ctx.getUserId());
    }

    // ==================== 调用日志 ====================

    /** 同步路径统一记调用日志（真实 token 优先，缺失时按字符数估算） */
    private void recordSyncCallLog(AgentExecutionContext ctx, AgentResult result, boolean success, String error) {
        int tokensInput = result.getInputTokens() > 0 ? result.getInputTokens()
                : (ctx.getUserMessage() != null ? ctx.getUserMessage().length() / 2 : 0);
        int tokensOutput = result.getOutputTokens() > 0 ? result.getOutputTokens()
                : (result.getAnswer() != null ? result.getAnswer().length() / 2 : 0);
        callLogClient.record(ctx.getUserId(),
                ctx.getActualModelName() != null ? ctx.getActualModelName() : "agent",
                ctx.getModelName() != null ? ctx.getModelName() : "auto",
                ctx.getUserMessage() != null ? ctx.getUserMessage() : "",
                result.getAnswer() != null ? result.getAnswer() : "",
                tokensInput, tokensOutput, result.getElapsedMs(), success, error);
    }

    /** 包装回调：记录调用日志 + 事件转发（call_log 用于成本/额度统计） */
    private AgentStreamCallback buildLoggingCallback(AgentExecutionContext ctx, AgentStreamCallback callback) {
        return new AgentStreamCallback() {
            private final StringBuilder fullContent = new StringBuilder();
            private final long start = System.currentTimeMillis();
            // [input, output]，由 AgentLoop / external 分支在 onDone 前通过 onUsage 回填
            private final int[] usage = new int[2];

            @Override
            public void onThinking() {
                callback.onThinking();
            }

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
            public void onUsage(int inputTokens, int outputTokens) {
                usage[0] = inputTokens;
                usage[1] = outputTokens;
            }

            @Override
            public void onDone(String fullReply) {
                if (fullReply != null) fullContent.append(fullReply);
                long latency = System.currentTimeMillis() - start;
                int tokensInput = usage[0] > 0 ? usage[0] : ctx.getUserMessage().length() / 2;
                int tokensOutput = usage[1] > 0 ? usage[1] : fullContent.length() / 2;
                callLogClient.record(ctx.getUserId(), ctx.getActualModelName() != null ? ctx.getActualModelName() : "agent",
                        ctx.getModelName() != null ? ctx.getModelName() : "auto",
                        ctx.getUserMessage(), fullContent.toString(),
                        tokensInput, tokensOutput,
                        latency, true, null);
                callback.onDone(fullReply);
            }

            @Override
            public void onError(Throwable error) {
                long latency = System.currentTimeMillis() - start;
                int tokensInput = usage[0] > 0 ? usage[0] : ctx.getUserMessage().length() / 2;
                int tokensOutput = usage[1] > 0 ? usage[1] : fullContent.length() / 2;
                callLogClient.record(ctx.getUserId(), ctx.getActualModelName() != null ? ctx.getActualModelName() : "agent",
                        ctx.getModelName() != null ? ctx.getModelName() : "auto",
                        ctx.getUserMessage(), fullContent.toString(),
                        tokensInput, tokensOutput,
                        latency, false, error.getMessage());
                callback.onError(error);
            }
        };
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

    /** 热重载工具注册表 + 子 Agent 名册 */
    public void reloadTools() {
        toolRegistry.reload();
        agentCatalog.reload();
        log.info("Tool registry and agent catalog reloaded");
    }
}

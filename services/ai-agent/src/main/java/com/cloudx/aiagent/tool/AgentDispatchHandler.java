package com.cloudx.aiagent.tool;

import com.cloudx.aiagent.agent.AgentExecutionContext;
import com.cloudx.aiagent.agent.AgentLoop;
import com.cloudx.aiagent.agent.AgentMessage;
import com.cloudx.aiagent.agent.AgentResult;
import com.cloudx.aiagent.service.AgentCatalog;
import com.cloudx.aiagent.service.ExternalAgentClient;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.stereotype.Component;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

/**
 * 子 Agent 调度处理器 — Agent-as-Tool 编排模式的执行核心
 *
 * <pre>
 * dispatch_agent(agent_name, task) 的执行流程：
 *   1. 校验编排深度（子 Agent 不能再调度，防止无限递归）
 *   2. 从 AgentCatalog 查找子 Agent 定义
 *   3. 构建子执行上下文（独立对话、depth+1、继承用户身份）
 *   4. 复用 AgentLoop 完整跑一遍子 Agent 的 ReAct 循环
 *   5. 子 Agent 的 token 消耗归集到父调用（统一计费，不重复记账）
 * </pre>
 *
 * <p>与 Claude Code 的 Agent 工具同构：新增子 Agent 只需插 agent_definition，
 * 通用助手通过动态名册无感知调度。
 */
@Slf4j
@Component
public class AgentDispatchHandler {

    private final ObjectProvider<AgentLoop> agentLoopProvider;
    private final AgentCatalog agentCatalog;
    private final ExternalAgentClient externalAgentClient;
    private final ObjectMapper objectMapper;

    /** 单次子 Agent 执行超时（子 Agent 最多 maxIterations 轮 LLM 调用，需留足时间） */
    private static final long SUB_AGENT_TIMEOUT_MS = 150_000;

    /** 最大编排深度：0=顶层，1=子 Agent；子 Agent 不允许再调度 */
    private static final int MAX_DEPTH = 1;

    public AgentDispatchHandler(ObjectProvider<AgentLoop> agentLoopProvider,
                                AgentCatalog agentCatalog,
                                ExternalAgentClient externalAgentClient,
                                ObjectMapper objectMapper) {
        this.agentLoopProvider = agentLoopProvider;
        this.agentCatalog = agentCatalog;
        this.externalAgentClient = externalAgentClient;
        this.objectMapper = objectMapper;
    }

    /**
     * 执行 dispatch_agent 工具调用
     *
     * @param argumentsJson LLM 生成的参数 JSON（agent_name / task）
     * @param parent        父 Agent 执行上下文
     * @return 结构化结果 JSON（作为 tool result 回到父 Agent 循环）
     */
    public String execute(String argumentsJson, AgentExecutionContext parent) {
        // 1. 编排深度守卫
        if (parent.getDepth() >= MAX_DEPTH) {
            return errorJson("已达到最大编排深度，子Agent不能再调度其他Agent");
        }

        // 2. 解析参数
        String agentName;
        String task;
        try {
            Map<String, Object> args = objectMapper.readValue(argumentsJson,
                    new com.fasterxml.jackson.core.type.TypeReference<Map<String, Object>>() {});
            agentName = args.get("agent_name") != null ? args.get("agent_name").toString().trim() : null;
            task = args.get("task") != null ? args.get("task").toString().trim() : null;
        } catch (Exception e) {
            return errorJson("参数解析失败，需要 JSON: {\"agent_name\": \"...\", \"task\": \"...\"}");
        }
        if (agentName == null || agentName.isBlank() || task == null || task.isBlank()) {
            return errorJson("agent_name 和 task 均不能为空");
        }

        // 3. 查找子 Agent 定义
        Map<String, Object> def = agentCatalog.getByName(agentName);
        if (def == null) {
            return errorJson("找不到子Agent \"" + agentName + "\"，可用列表：\n" + agentCatalog.buildRoster());
        }
        if (toInt(def.get("status"), 0) != 1) {
            return errorJson("子Agent \"" + agentName + "\" 已停用");
        }

        // 4. 防自调度（Agent 调度自己会死循环）
        Long targetId = toLong(def.get("id"));
        if (targetId != null && targetId.equals(parent.getAgentId())) {
            return errorJson("不能调度自己");
        }

        // 5. 按执行类型分流：external → HTTP 任务委托；internal → 本地 AgentLoop
        long start = System.currentTimeMillis();
        String answer;
        int inputTokens;
        int outputTokens;
        int iterations = 1;
        int toolCalls = 0;

        if ("external".equals(def.get("executionType"))) {
            // ---- 第三方 Agent：黑盒委托（对方内部有自己的循环）----
            ExternalAgentClient.ExternalAgentTarget target = agentCatalog.getExternalTarget(targetId);
            if (target == null) {
                return errorJson("第三方Agent \"" + agentName + "\" 配置不完整（缺少 endpointUrl 或 Key），请联系管理员");
            }
            AgentMessage taskMsg = AgentMessage.builder().role("user").content(task).build();
            ExternalAgentClient.ExternalResult ext = externalAgentClient.execute(
                    target, List.of(taskMsg), parent.getUserId());
            if (!ext.success()) {
                // 失败也交给 L0 决策：它可以换其他 Agent 或告知用户
                return errorJson("第三方Agent \"" + agentName + "\" 调用失败: " + ext.error());
            }
            answer = ext.answer();
            inputTokens = ext.inputTokens();
            outputTokens = ext.outputTokens();
        } else {
            // ---- 内置 Agent：构建子执行上下文（独立对话，继承用户身份），本地跑 ReAct 循环
            AgentExecutionContext child = AgentExecutionContext.builder()
                    .userId(parent.getUserId())
                    .callerRole(parent.getCallerRole())
                    .agentId(targetId)
                    .agentName((String) def.get("name"))
                    .systemPrompt((String) def.get("systemPrompt"))
                    .userMessage(task)
                    .history(null)
                    .temperature(toDouble(def.get("temperature"), 0.7))
                    .maxIterations(toInt(def.get("maxIterations"), 3))
                    .depth(parent.getDepth() + 1)
                    .stream(false)
                    .build();

            try {
                AgentResult subResult = CompletableFuture
                        .supplyAsync(() -> agentLoopProvider.getObject().execute(child, null))
                        .get(SUB_AGENT_TIMEOUT_MS, TimeUnit.MILLISECONDS);
                answer = subResult.getAnswer();
                inputTokens = subResult.getInputTokens();
                outputTokens = subResult.getOutputTokens();
                iterations = subResult.getIterations();
                toolCalls = subResult.getToolSteps().size();
            } catch (TimeoutException e) {
                log.warn("dispatch_agent [{}] timed out after {}ms", agentName, SUB_AGENT_TIMEOUT_MS);
                return errorJson("子Agent \"" + agentName + "\" 执行超时（" + SUB_AGENT_TIMEOUT_MS / 1000 + "s），请拆分任务后重试");
            } catch (Exception e) {
                Throwable cause = e.getCause() != null ? e.getCause() : e;
                log.error("dispatch_agent [{}] failed: {}", agentName, cause.getMessage());
                return errorJson("子Agent \"" + agentName + "\" 执行失败: " + cause.getMessage());
            }
        }
        long elapsed = System.currentTimeMillis() - start;

        // 6. 子 Agent token 归集到父调用（统一计费，内置与第三方口径一致）
        parent.addChildTokens(inputTokens, outputTokens);

        log.info("dispatch_agent [{}] ({}) done in {}ms, tokens={}/{}, answer length={}",
                agentName, def.get("executionType"), elapsed, inputTokens, outputTokens,
                answer != null ? answer.length() : 0);

        // 7. 结构化结果返回给父 Agent
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("agent", def.get("name"));
        out.put("success", true);
        out.put("answer", answer != null ? answer : "（子Agent未返回答案）");
        out.put("iterations", iterations);
        out.put("toolCalls", toolCalls);
        out.put("elapsedMs", elapsed);
        out.put("message", "子Agent执行完成，请基于以上结果继续，不要再次调度相同任务");
        return toJson(out);
    }

    private String errorJson(String message) {
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("success", false);
        out.put("error", message);
        return toJson(out);
    }

    private String toJson(Map<String, Object> out) {
        try {
            return objectMapper.writeValueAsString(out);
        } catch (Exception e) {
            return "{\"success\": false, \"error\": \"结果序列化失败\"}";
        }
    }

    private Long toLong(Object obj) {
        if (obj == null) return null;
        if (obj instanceof Number n) return n.longValue();
        try {
            return Long.parseLong(obj.toString());
        } catch (Exception e) {
            return null;
        }
    }

    private int toInt(Object obj, int defaultVal) {
        if (obj == null) return defaultVal;
        if (obj instanceof Number n) return n.intValue();
        try {
            return Integer.parseInt(obj.toString());
        } catch (Exception e) {
            return defaultVal;
        }
    }

    private double toDouble(Object obj, double defaultVal) {
        if (obj == null) return defaultVal;
        if (obj instanceof Number n) return n.doubleValue();
        try {
            return Double.parseDouble(obj.toString());
        } catch (Exception e) {
            return defaultVal;
        }
    }
}
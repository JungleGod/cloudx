package com.cloudx.aiagent.agent;

import lombok.Builder;
import lombok.Data;

import java.util.List;

/**
 * Agent 执行上下文 — 一次 Agent 调用的完整参数
 */
@Data
@Builder
public class AgentExecutionContext {

    /** 编排深度（0=顶层 Agent，1=被 dispatch_agent 调度的子 Agent），防止无限递归 */
    @Builder.Default
    private int depth = 0;

    /** 子 Agent 消耗的 token 归集器 [input, output]，dispatch_agent 执行时累加，随父调用统一计费 */
    @Builder.Default
    private long[] childTokens = new long[2];

    /** 平台用户 ID */
    private Long userId;

    /** 用户角色（admin / user），用于工具权限过滤 */
    private String callerRole;

    /** Agent 定义 ID */
    private Long agentId;

    /** Agent 名称 */
    private String agentName;

    /** 系统提示词 */
    private String systemPrompt;

    /** 用户当前消息 */
    private String userMessage;

    /** 对话历史 */
    private List<AgentMessage> history;

    /** 绑定的模型名（null 则自动路由） */
    private String modelName;

    /** 温度参数 */
    private Double temperature;

    /** 最大工具调用迭代次数 */
    private Integer maxIterations;

    /** API Key（由 load balancer 选出） */
    private String apiKey;

    /** API Base URL */
    private String baseUrl;

    /** 实际的模型名（传给 API 的 model 参数） */
    private String actualModelName;

    /** 是否流式输出 */
    private boolean stream;

    // ---- 默认值 ----

    public double getEffectiveTemperature() {
        return temperature != null ? temperature : 0.7;
    }

    public int getEffectiveMaxIterations() {
        return maxIterations != null ? maxIterations : 5;
    }

    /** 累加子 Agent 消耗的 token */
    public void addChildTokens(int input, int output) {
        childTokens[0] += input;
        childTokens[1] += output;
    }

    /** 取走子 Agent 累计的 token（取后清零，保证归集不重复） */
    public long[] takeChildTokens() {
        long[] taken = childTokens;
        childTokens = new long[2];
        return taken;
    }
}

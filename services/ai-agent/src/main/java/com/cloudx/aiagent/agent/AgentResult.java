package com.cloudx.aiagent.agent;

import lombok.Builder;
import lombok.Data;

import java.util.ArrayList;
import java.util.List;

/**
 * Agent 执行结果 — 包含最终答案和执行审计信息
 */
@Data
@Builder
public class AgentResult {

    /** 最终答案文本 */
    private String answer;

    /** 执行的总迭代次数（每次 LLM 调用算一次迭代） */
    private int iterations;

    /** 工具调用步骤审计 */
    @Builder.Default
    private List<ToolStep> toolSteps = new ArrayList<>();

    /** 总 token 消耗（prompt_tokens + completion_tokens） */
    @Builder.Default
    private int totalTokens = 0;

    /** 输入 token 消耗（prompt_tokens，跨所有迭代累计） */
    @Builder.Default
    private int inputTokens = 0;

    /** 输出 token 消耗（completion_tokens，跨所有迭代累计） */
    @Builder.Default
    private int outputTokens = 0;

    /** 执行耗时（毫秒） */
    private long elapsedMs;

    /** 是否被中断（达到最大迭代次数） */
    private boolean interrupted;

    /** 客户端工具模式（OpenAI 兼容 /v1 透传）下模型请求的原始 tool_calls，由调用方执行后回传 */
    private List<AgentMessage.ToolCall> clientToolCalls;

    // ---- inner record: ToolStep ----

    @Data
    @Builder
    public static class ToolStep {
        /** 步骤序号 */
        private int iteration;

        /** 工具名称 */
        private String toolName;

        /** 传入参数 JSON */
        private String arguments;

        /** 返回结果 */
        private String result;

        /** 是否成功 */
        private boolean success;

        /** 执行耗时 ms */
        private long elapsedMs;
    }

    public void addToolStep(ToolStep step) {
        if (toolSteps == null) toolSteps = new ArrayList<>();
        toolSteps.add(step);
    }

    /** 累加一次 LLM 调用的 token（输入/输出分开，total 同步累加） */
    public void addUsage(int input, int output) {
        this.inputTokens += input;
        this.outputTokens += output;
        this.totalTokens += input + output;
    }
}

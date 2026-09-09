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

    /** 执行耗时（毫秒） */
    private long elapsedMs;

    /** 是否被中断（达到最大迭代次数） */
    private boolean interrupted;

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

    public void addTokens(int tokens) {
        this.totalTokens += tokens;
    }
}

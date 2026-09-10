package com.cloudx.aiagent.agent;

/**
 * Agent 流式回调 — 比普通 StreamCallback 更丰富，
 * 增加 thinking / tool_call / tool_result 等 Agent 特有事件
 */
public interface AgentStreamCallback {

    /**
     * LLM 正在思考（stream 开始前触发）
     */
    default void onThinking() {}

    /**
     * LLM 开始请求工具调用
     * @param toolName 工具名
     * @param callId   tool_call id
     */
    default void onToolCallStart(String toolName, String callId) {}

    /**
     * 工具参数流式增量（工具调用的 arguments 可能是流式返回的）
     * @param callId tool_call id
     * @param delta  参数 JSON 增量
     */
    default void onToolCallArgs(String callId, String delta) {}

    /**
     * 工具参数接收完毕，开始执行工具
     * @param toolName  工具名
     * @param arguments 完整参数 JSON
     */
    default void onToolCallExecuting(String toolName, String arguments) {}

    /**
     * 工具执行结果
     * @param toolName 工具名
     * @param result   执行结果字符串
     * @param success  是否成功
     * @param elapsedMs 耗时
     */
    default void onToolResult(String toolName, String result, boolean success, long elapsedMs) {}

    /**
     * LLM 返回文本 token
     */
    default void onToken(String token) {}

    /**
     * 流式对话正常完成
     * @param fullContent 完整回复文本（非流式模式用）
     */
    default void onDone(String fullContent) {}

    /**
     * 报告本轮 Agent 执行累计的真实 token 用量（输入/输出），在 onDone 之前触发
     * @param inputTokens  累计输入 token（prompt_tokens）
     * @param outputTokens 累计输出 token（completion_tokens）
     */
    default void onUsage(int inputTokens, int outputTokens) {}

    /**
     * 出错
     */
    default void onError(Throwable error) {}
}

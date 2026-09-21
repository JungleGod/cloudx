package com.cloudx.aiagent.provider;

/**
 * 流式回调 — Provider 通过此接口向上层报告流式对话状态
 */
public interface StreamCallback {

    /**
     * 路由器宣告实际选中的模型（failover 切换时可能再次触发）
     * @param modelName 实际承接本次调用的模型名
     */
    default void onRouted(String modelName) {}

    /** LLM 返回了一个 token 片段 */
    void onToken(String token);

    /**
     * 流式对话正常完成
     * @param inputTokens  模型返回的输入 token 数（prompt_tokens），无 usage 时为 0
     * @param outputTokens 模型返回的输出 token 数（completion_tokens），无 usage 时为 0
     */
    void onComplete(int inputTokens, int outputTokens);

    /** 流式对话出错 */
    void onError(Throwable error);
}
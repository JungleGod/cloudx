package com.cloudx.aiagent.provider;

/**
 * 流式回调 — Provider 通过此接口向上层报告流式对话状态
 */
public interface StreamCallback {

    /** LLM 返回了一个 token 片段 */
    void onToken(String token);

    /** 流式对话正常完成 */
    void onComplete();

    /** 流式对话出错 */
    void onError(Throwable error);
}
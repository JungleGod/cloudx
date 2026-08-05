package com.cloudx.aiagent.provider;

/**
 * 模型提供者接口 — 所有 AI 厂商实现此接口
 */
public interface ModelProvider {

    /** 模型名称 */
    String getModelName();

    /** 是否可用（有 Key 且没有熔断） */
    boolean isAvailable();

    /** 发送对话请求 */
    String chat(String message);

    /** 记录成功调用 */
    void recordSuccess();

    /** 记录失败调用，返回是否应熔断 */
    boolean recordFailure();
}

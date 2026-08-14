package com.cloudx.aiagent.provider;

/**
 * 模型提供者接口 — 所有 AI 厂商实现此接口
 */
public interface ModelProvider {

    /** 模型名称 */
    String getModelName();

    /** 是否可用（有 Key 且没有熔断） */
    boolean isAvailable();

    /** 发送对话请求（同步） */
    String chat(String message);

    /** 流式对话 — 每个 token 回调一次 */
    void streamChat(String message, StreamCallback callback);

    /** 多模态对话 — 图片 + 文字 */
    String chatMultimodal(String text, java.util.List<String> base64Images);

    /** 记录成功调用 */
    void recordSuccess();

    /** 记录失败调用，返回是否应熔断 */
    boolean recordFailure();

    /** 当前连续失败次数 */
    int getFailureCount();

    /** 熔断器状态: CLOSED / OPEN / HALF_OPEN */
    String getCircuitState();
}

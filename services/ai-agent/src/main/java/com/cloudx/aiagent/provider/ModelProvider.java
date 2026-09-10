package com.cloudx.aiagent.provider;

/**
 * 模型提供者接口 — 所有 AI 厂商实现此接口
 */
public interface ModelProvider {

    /** 模型名称 */
    String getModelName();

    /** 是否可用（有 Key 且没有熔断） */
    boolean isAvailable();

    /** 发送对话请求（同步），返回文本 */
    String chat(String message);

    /**
     * 发送对话请求并返回真实 token 用量（来自模型返回的 usage 字段）。
     * 默认实现回退到 {@link #chat(String)}，token 记为 0（上层会按字符估算兜底）。
     */
    default ChatResult chatDetailed(String message) {
        return new ChatResult(chat(message), 0, 0);
    }

    /** 流式对话 — 每个 token 回调一次 */
    void streamChat(String message, StreamCallback callback);

    /** 多模态对话 — 图片 + 文字 */
    String chatMultimodal(String text, java.util.List<String> base64Images);

    /** 多模态对话并返回真实 token 用量 */
    default ChatResult chatMultimodalDetailed(String text, java.util.List<String> base64Images) {
        return new ChatResult(chatMultimodal(text, base64Images), 0, 0);
    }

    /** 记录成功调用 */
    void recordSuccess();

    /** 记录失败调用，返回是否应熔断 */
    boolean recordFailure();

    /** 当前连续失败次数 */
    int getFailureCount();

    /** 熔断器状态: CLOSED / OPEN / HALF_OPEN */
    String getCircuitState();

    /** 一次调用的结果：文本 + 真实 token 用量（input/output） */
    record ChatResult(String reply, int inputTokens, int outputTokens) {}
}

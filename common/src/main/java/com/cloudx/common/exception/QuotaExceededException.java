package com.cloudx.common.exception;

/**
 * 用户月度额度已用完（429）
 * 由 ai-agent 在调用模型前抛出，拦截超额度请求。
 */
public class QuotaExceededException extends RuntimeException {

    public QuotaExceededException(String message) {
        super(message);
    }
}
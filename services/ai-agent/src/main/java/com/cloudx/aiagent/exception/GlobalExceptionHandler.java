package com.cloudx.aiagent.exception;

import com.cloudx.common.exception.QuotaExceededException;
import com.cloudx.common.result.R;
import lombok.extern.slf4j.Slf4j;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

/**
 * ai-agent 全局异常处理。
 * 仅处理额度超限（429），其余异常保持 Spring 默认行为不变。
 */
@Slf4j
@RestControllerAdvice
public class GlobalExceptionHandler {

    @ExceptionHandler(QuotaExceededException.class)
    public R<?> handleQuotaExceeded(QuotaExceededException e) {
        log.warn("额度超限: {}", e.getMessage());
        return R.fail(429, e.getMessage());
    }
}
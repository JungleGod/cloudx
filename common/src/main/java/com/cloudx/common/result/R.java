package com.cloudx.common.result;

import lombok.Data;
import org.springframework.http.HttpStatus;

/**
 * 统一响应体
 */
@Data
public class R<T> {

    private int code;
    private String msg;
    private T data;

    private R(int code, String msg, T data) {
        this.code = code;
        this.msg = msg;
        this.data = data;
    }

    // ===== 成功 =====

    public static <T> R<T> ok() {
        return new R<>(HttpStatus.OK.value(), "success", null);
    }

    public static <T> R<T> ok(T data) {
        return new R<>(HttpStatus.OK.value(), "success", data);
    }

    public static <T> R<T> ok(String msg, T data) {
        return new R<>(HttpStatus.OK.value(), msg, data);
    }

    // ===== 失败 =====

    public static <T> R<T> fail(String msg) {
        return new R<>(HttpStatus.INTERNAL_SERVER_ERROR.value(), msg, null);
    }

    public static <T> R<T> fail(int code, String msg) {
        return new R<>(code, msg, null);
    }

    public static <T> R<T> fail(int code, String msg, T data) {
        return new R<>(code, msg, data);
    }
}

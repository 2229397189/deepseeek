package com.lq.deepseek.common;

import lombok.Data;

import java.io.Serializable;

/**
 * 统一响应体。
 */
@Data
public class Result<T> implements Serializable {

    private int code;
    private String message;
    private T data;
    private long timestamp = System.currentTimeMillis();

    public static <T> Result<T> ok(T data) {
        Result<T> r = new Result<>();
        r.code = ErrorCode.OK.getCode();
        r.message = ErrorCode.OK.getMessage();
        r.data = data;
        return r;
    }

    public static <T> Result<T> ok() {
        return ok(null);
    }

    public static <T> Result<T> fail(ErrorCode ec) {
        return fail(ec.getCode(), ec.getMessage());
    }

    public static <T> Result<T> fail(ErrorCode ec, String message) {
        return fail(ec.getCode(), message);
    }

    public static <T> Result<T> fail(int code, String message) {
        Result<T> r = new Result<>();
        r.code = code;
        r.message = message;
        return r;
    }

    public boolean isSuccess() {
        return this.code == ErrorCode.OK.getCode();
    }
}

package com.paperpilot.api.common;

/**
 * 业务异常，由 {@link GlobalExceptionHandler} 转为统一信封响应.
 *
 * <p>{@code code} 同时作为 HTTP 状态码使用。
 */
public class ApiException extends RuntimeException {

    private final int code;

    public ApiException(int code, String message) {
        super(message);
        this.code = code;
    }

    public ApiException(ErrorCode errorCode) {
        this(errorCode.getCode(), errorCode.getDefaultMessage());
    }

    public ApiException(ErrorCode errorCode, String message) {
        this(errorCode.getCode(), message);
    }

    public int getCode() {
        return code;
    }
}

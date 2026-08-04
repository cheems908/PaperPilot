package com.paperpilot.api.common;

/**
 * 统一错误码.
 *
 * <p>code 与 HTTP 状态码对齐：前端信封约定 {@code code === 0} 为成功，
 * 非 0 即错误。服务端用 {@code ResponseEntity.status(code)} 一并返回 HTTP 状态。
 */
public enum ErrorCode {

    BAD_REQUEST(400, "请求参数错误"),
    NOT_FOUND(404, "资源不存在"),
    CONFLICT(409, "状态冲突"),
    INTERNAL(500, "服务器内部错误");

    private final int code;
    private final String defaultMessage;

    ErrorCode(int code, String defaultMessage) {
        this.code = code;
        this.defaultMessage = defaultMessage;
    }

    public int getCode() {
        return code;
    }

    public String getDefaultMessage() {
        return defaultMessage;
    }
}

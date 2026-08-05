package com.paperpilot.api.common;

/**
 * 统一响应信封 {@code { code, message, data, requestId }}.
 *
 * <p>{@code code === 0} 表示成功；非 0 表示错误（与 HTTP 状态码对齐）。
 * 前端 {@code api.js} 按此解包（仅依赖 {@code code}/{@code message}），
 * 调用方直接拿到 {@code data}；新增的 {@code requestId} 不改变既有语义。
 *
 * <p>{@code requestId} 由 {@link RequestIdFilter} 写入 MDC 后，本类在构造时读取：
 * 成功与异常响应统一携带，便于全链路追踪。
 */
public record ApiResponse<T>(int code, String message, T data, String requestId) {

    public static <T> ApiResponse<T> ok(T data) {
        return new ApiResponse<>(0, "ok", data, RequestId.current());
    }

    public static <T> ApiResponse<T> ok() {
        return ok(null);
    }

    public static <T> ApiResponse<T> error(int code, String message) {
        return new ApiResponse<>(code, message, null, RequestId.current());
    }
}

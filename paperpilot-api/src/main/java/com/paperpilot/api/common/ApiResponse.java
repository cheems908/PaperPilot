package com.paperpilot.api.common;

/**
 * 统一响应信封 {@code { code, message, data }}.
 *
 * <p>{@code code === 0} 表示成功；非 0 表示错误（与 HTTP 状态码对齐）。
 * 前端 {@code api.js} 按此解包，调用方直接拿到 {@code data}。
 */
public record ApiResponse<T>(int code, String message, T data) {

    public static <T> ApiResponse<T> ok(T data) {
        return new ApiResponse<>(0, "ok", data);
    }

    public static <T> ApiResponse<T> ok() {
        return ok(null);
    }

    public static <T> ApiResponse<T> error(int code, String message) {
        return new ApiResponse<>(code, message, null);
    }
}

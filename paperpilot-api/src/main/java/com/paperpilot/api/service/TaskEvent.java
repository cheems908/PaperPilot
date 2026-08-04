package com.paperpilot.api.service;

import java.time.LocalDateTime;

/**
 * SSE 事件载荷.
 *
 * <p>前端 {@code taskEventsPolicy.js} 依据 {@code state} 判定是否终态
 * （{@code SUCCEEDED / FAILED / CANCELLED} 均视为终态，连接自动关闭）。
 */
public record TaskEvent(
        Long taskId,
        String state,
        String stage,
        String message,
        LocalDateTime at) {

    public static TaskEvent of(Long taskId, String state, String stage, String message) {
        return new TaskEvent(taskId, state, stage, message, LocalDateTime.now());
    }
}

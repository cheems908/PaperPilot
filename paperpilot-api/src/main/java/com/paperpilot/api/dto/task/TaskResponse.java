package com.paperpilot.api.dto.task;

/** 创建任务响应（202 Accepted）. */
public record TaskResponse(
        Long taskId,
        String status,
        String eventsUrl) {
}

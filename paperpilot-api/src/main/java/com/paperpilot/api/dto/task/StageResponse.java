package com.paperpilot.api.dto.task;

import java.time.LocalDateTime;

/** 单阶段执行响应. */
public record StageResponse(
        String stage,
        Integer attempt,
        String status,
        String snapshot,
        String errorMessage,
        LocalDateTime updatedAt) {
}

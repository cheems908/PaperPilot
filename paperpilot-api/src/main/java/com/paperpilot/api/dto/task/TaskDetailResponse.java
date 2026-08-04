package com.paperpilot.api.dto.task;

import java.time.LocalDateTime;

/** 任务详情响应. */
public record TaskDetailResponse(
        Long id,
        Long projectId,
        Long sourceFileId,
        Long paperId,
        Long repositoryId,
        String status,
        String requestKey,
        LocalDateTime createdAt,
        LocalDateTime updatedAt) {
}

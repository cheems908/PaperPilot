package com.paperpilot.api.dto.progress;

/**
 * 进度查询视图：MySQL status 为真相来源，Redis 仅补充 progress/message.
 */
public record TaskProgressView(
        Long taskId,
        String status,
        String stage,
        Integer progress,
        String message) {
}

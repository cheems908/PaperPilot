package com.paperpilot.api.dto.project;

import java.time.LocalDateTime;

/** 项目响应. */
public record ProjectResponse(
        Long id,
        String name,
        String description,
        LocalDateTime createdAt) {
}

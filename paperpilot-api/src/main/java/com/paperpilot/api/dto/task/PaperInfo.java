package com.paperpilot.api.dto.task;

/** 任务关联论文的摘要信息. */
public record PaperInfo(
        Long paperId,
        String title) {
}

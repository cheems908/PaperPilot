package com.paperpilot.api.dto.task;

/** 任务关联仓库的摘要信息. */
public record RepositoryInfo(
        Long repositoryId,
        String githubUrl) {
}

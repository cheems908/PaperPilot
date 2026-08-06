package com.paperpilot.api.dto.task;

import java.util.List;

/**
 * 任务结果响应.
 *
 * <p>{@code result} 为各阶段快照聚合结果，MVP 阶段由 Worker 执行后填充；
 * 当前（无 Worker）为各完成阶段的 snapshot 映射，未执行阶段返回空。
 * {@code mappingStatus}：{@code NO_MAPPINGS} / {@code NEEDS_REVIEW} / {@code CANDIDATES}。
 */
public record TaskResultResponse(
        Long taskId,
        String status,
        PaperInfo paper,
        RepositoryInfo repository,
        List<StageResponse> stages,
        Object result,
        String mappingStatus,
        List<MappingResult> mappings) {
}

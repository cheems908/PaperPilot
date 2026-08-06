package com.paperpilot.api.dto.task;

import java.math.BigDecimal;

/** 结果 API 返回的单个候选映射（代码证据 + 分数 + 状态）. */
public record MappingCandidateResult(
        String qualifiedName,
        String filePath,
        Integer startLine,
        BigDecimal totalScore,
        String status,
        String evidence) {
}

package com.paperpilot.api.dto.task;

import java.math.BigDecimal;
import java.util.List;

/** 结果 API 返回的单个候选映射（代码证据 + 分数 + 状态）. */
public record MappingCandidateResult(
        String qualifiedName,
        String filePath,
        Integer startLine,
        String commitSha,
        BigDecimal semanticScore,
        BigDecimal symbolScore,
        BigDecimal keywordScore,
        BigDecimal documentationScore,
        BigDecimal verificationScore,
        BigDecimal totalScore,
        String status,
        Boolean degraded,
        String verificationReason,
        List<String> matchedTokens,
        String evidence) {
}

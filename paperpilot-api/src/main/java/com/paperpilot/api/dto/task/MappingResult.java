package com.paperpilot.api.dto.task;

import java.util.List;

/** 结果 API 返回的概念及其候选映射（论文证据 + 代码证据）. */
public record MappingResult(
        String term,
        String source,
        String evidenceText,
        List<MappingCandidateResult> candidates) {
}

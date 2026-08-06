package com.paperpilot.api.dto.task;

import com.paperpilot.api.dto.mapping.ConceptMentionDto;

import java.util.List;

/** 结果 API 返回的概念及其候选映射（论文证据 + 代码证据）. */
public record MappingResult(
        String conceptId,
        String term,
        List<String> aliases,
        String extractorVersion,
        List<ConceptMentionDto> mentions,
        String decision,
        String abstentionReason,
        String source,
        String evidenceText,
        List<MappingCandidateResult> candidates) {
}

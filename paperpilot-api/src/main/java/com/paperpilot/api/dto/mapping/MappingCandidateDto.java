package com.paperpilot.api.dto.mapping;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonProperty;

import java.math.BigDecimal;
import java.util.List;

/** 单个候选映射：代码坐标 + 分项分数 + 状态 + 证据. */
@JsonIgnoreProperties(ignoreUnknown = true)
public record MappingCandidateDto(
        @JsonProperty("symbolRef") SymbolRef symbolRef,
        @JsonProperty("symbolScore") BigDecimal symbolScore,
        @JsonProperty("semanticScore") BigDecimal semanticScore,
        @JsonProperty("keywordScore") BigDecimal keywordScore,
        @JsonProperty("documentationScore") BigDecimal documentationScore,
        @JsonProperty("verificationScore") BigDecimal verificationScore,
        @JsonProperty("totalScore") BigDecimal totalScore,
        @JsonProperty("status") String status,
        @JsonProperty("degraded") Boolean degraded,
        @JsonProperty("verificationReason") String verificationReason,
        @JsonProperty("matchedTokens") List<String> matchedTokens,
        @JsonProperty("codeEvidence") String codeEvidence) {
}

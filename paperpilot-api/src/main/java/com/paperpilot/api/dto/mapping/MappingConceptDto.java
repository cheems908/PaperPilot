package com.paperpilot.api.dto.mapping;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonProperty;

import java.util.List;

/** 论文概念 + 候选映射列表（含论文证据）. */
@JsonIgnoreProperties(ignoreUnknown = true)
public record MappingConceptDto(
        @JsonProperty("term") String term,
        @JsonProperty("source") String source,
        @JsonProperty("section") String section,
        @JsonProperty("page") Integer page,
        @JsonProperty("evidenceText") String evidenceText,
        @JsonProperty("paragraphId") String paragraphId,
        @JsonProperty("candidates") List<MappingCandidateDto> candidates) {
}

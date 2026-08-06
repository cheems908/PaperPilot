package com.paperpilot.api.dto.mapping;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonProperty;

import java.util.List;

/** 论文概念 + 候选映射列表（含论文证据）. */
@JsonIgnoreProperties(ignoreUnknown = true)
public record MappingConceptDto(
        @JsonProperty("conceptId") String conceptId,
        @JsonProperty("term") String term,
        @JsonProperty("aliases") List<String> aliases,
        @JsonProperty("extractorVersion") String extractorVersion,
        @JsonProperty("mentions") List<ConceptMentionDto> mentions,
        @JsonProperty("source") String source,
        @JsonProperty("section") String section,
        @JsonProperty("page") Integer page,
        @JsonProperty("evidenceText") String evidenceText,
        @JsonProperty("paragraphId") String paragraphId,
        @JsonProperty("decision") String decision,
        @JsonProperty("abstentionReason") String abstentionReason,
        @JsonProperty("candidates") List<MappingCandidateDto> candidates) {
}

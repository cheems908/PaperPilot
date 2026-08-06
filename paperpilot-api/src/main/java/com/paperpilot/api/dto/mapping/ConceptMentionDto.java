package com.paperpilot.api.dto.mapping;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonProperty;

/** Stable paper evidence anchor for one concept mention. */
@JsonIgnoreProperties(ignoreUnknown = true)
public record ConceptMentionDto(
        @JsonProperty("section") String section,
        @JsonProperty("page") Integer page,
        @JsonProperty("paragraphId") String paragraphId,
        @JsonProperty("evidenceText") String evidenceText) {
}

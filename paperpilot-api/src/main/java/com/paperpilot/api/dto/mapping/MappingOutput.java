package com.paperpilot.api.dto.mapping;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonProperty;

import java.util.List;
import java.util.Map;

/** MAP_CONCEPTS 阶段输出（与 Python MappingOutput 一致）. */
@JsonIgnoreProperties(ignoreUnknown = true)
public record MappingOutput(
        @JsonProperty("commitSha") String commitSha,
        @JsonProperty("concepts") List<MappingConceptDto> concepts,
        @JsonProperty("stats") Map<String, Integer> stats) {
}

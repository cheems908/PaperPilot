package com.paperpilot.api.dto.indexer;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonProperty;

import java.util.List;
import java.util.Map;

/** INDEX_CODE 阶段输出（与 Python IndexOutput 一致）. */
@JsonIgnoreProperties(ignoreUnknown = true)
public record IndexResult(
        @JsonProperty("repo") String repo,
        @JsonProperty("commitSha") String commitSha,
        @JsonProperty("files") List<FileSymbols> files,
        @JsonProperty("warnings") List<String> warnings,
        @JsonProperty("stats") Map<String, Integer> stats) {
}

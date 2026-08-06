package com.paperpilot.api.dto.mapping;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonProperty;

/** 候选映射引用的代码坐标（与 code_symbol 稳定键对齐）. */
@JsonIgnoreProperties(ignoreUnknown = true)
public record SymbolRef(
        @JsonProperty("filePath") String filePath,
        @JsonProperty("qualifiedName") String qualifiedName,
        @JsonProperty("name") String name,
        @JsonProperty("startLine") Integer startLine,
        @JsonProperty("commitSha") String commitSha) {
}

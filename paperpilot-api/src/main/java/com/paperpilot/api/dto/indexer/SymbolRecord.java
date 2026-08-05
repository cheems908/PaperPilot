package com.paperpilot.api.dto.indexer;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonProperty;

/**
 * 代码符号记录（与 Python AST 索引输出一致）：稳定键为 qualifiedName + startLine.
 */
@JsonIgnoreProperties(ignoreUnknown = true)
public record SymbolRecord(
        @JsonProperty("kind") String kind,
        @JsonProperty("name") String name,
        @JsonProperty("qualifiedName") String qualifiedName,
        @JsonProperty("signature") String signature,
        @JsonProperty("docstring") String docstring,
        @JsonProperty("startLine") Integer startLine,
        @JsonProperty("endLine") Integer endLine,
        @JsonProperty("parent") String parent) {
}

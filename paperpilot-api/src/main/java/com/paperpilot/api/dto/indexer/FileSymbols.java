package com.paperpilot.api.dto.indexer;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonProperty;

import java.util.List;

/** 单个文件的符号集合（path 为仓库相对 POSIX 路径）. */
@JsonIgnoreProperties(ignoreUnknown = true)
public record FileSymbols(
        @JsonProperty("path") String path,
        @JsonProperty("symbols") List<SymbolRecord> symbols) {
}

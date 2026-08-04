package com.paperpilot.api.dto.snapshot;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonProperty;

/**
 * 输出快照的 artifact 引用：只记录类型与定位，不写入 artifact 内容.
 */
@JsonIgnoreProperties(ignoreUnknown = true)
public record StageArtifactRef(
        @JsonProperty("type") String type,
        @JsonProperty("path") String path) {

    public StageArtifactRef {
        if (type == null || path == null) {
            throw new IllegalArgumentException("missing required field in stage artifact ref");
        }
    }
}

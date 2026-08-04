package com.paperpilot.api.dto.snapshot;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonProperty;

/**
 * 受控资源引用（输入快照的 {@code source}）.
 *
 * <p>只记录资源定位（fileId + storagePath），不嵌入全文、TEI XML 或大对象。
 */
@JsonIgnoreProperties(ignoreUnknown = true)
public record StageResourceRef(
        @JsonProperty("fileId") Long fileId,
        @JsonProperty("storagePath") String storagePath) {

    public StageResourceRef {
        if (fileId == null || storagePath == null) {
            throw new IllegalArgumentException("missing required field in stage resource ref");
        }
    }
}

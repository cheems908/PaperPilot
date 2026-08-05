package com.paperpilot.api.dto.snapshot;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonProperty;

/**
 * 受控资源引用（输入快照的 {@code source}）.
 *
 * <p>只记录资源定位（fileId + 相对 storage root 的逻辑路径 + sha256），不嵌入全文、
 * TEI XML 或大对象。Worker 将路径 resolve 后校验位于 storage root 内并核对 sha256。
 */
@JsonIgnoreProperties(ignoreUnknown = true)
public record StageResourceRef(
        @JsonProperty("fileId") Long fileId,
        @JsonProperty("storagePath") String storagePath,
        @JsonProperty("sha256") String sha256) {

    public StageResourceRef(Long fileId, String storagePath) {
        this(fileId, storagePath, null);
    }

    public StageResourceRef {
        if (fileId == null || storagePath == null) {
            throw new IllegalArgumentException("missing required field in stage resource ref");
        }
    }
}

package com.paperpilot.api.dto.snapshot;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonProperty;

import java.util.List;
import java.util.Map;

/**
 * 阶段输出快照：只记录 worker 版本、摘要与 artifact 引用，不写全文/符号全集.
 *
 * <p>契约示例：
 * <pre>{@code
 * {"schemaVersion":1,"workerVersion":"0.1.0","artifactRefs":[],
 *  "summary":{"sectionCount":12,"conceptCount":8}}
 * }</pre>
 * artifactRefs / summary 允许缺省（反序列化为空集合）。
 */
@JsonIgnoreProperties(ignoreUnknown = true)
public record StageOutputSnapshot(
        @JsonProperty("schemaVersion") int schemaVersion,
        @JsonProperty("workerVersion") String workerVersion,
        @JsonProperty("artifactRefs") List<StageArtifactRef> artifactRefs,
        @JsonProperty("summary") Map<String, Object> summary) {

    public StageOutputSnapshot {
        if (schemaVersion != StageSnapshotContract.SCHEMA_VERSION) {
            throw new IllegalArgumentException("unsupported schemaVersion: " + schemaVersion);
        }
        if (workerVersion == null) {
            throw new IllegalArgumentException("missing required field workerVersion");
        }
        if (artifactRefs == null) {
            artifactRefs = List.of();
        }
        if (summary == null) {
            summary = Map.of();
        }
    }
}

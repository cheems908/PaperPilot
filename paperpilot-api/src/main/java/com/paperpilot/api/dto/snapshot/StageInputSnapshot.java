package com.paperpilot.api.dto.snapshot;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonProperty;
import com.paperpilot.api.domain.enums.TaskStage;

/**
 * 阶段输入快照：记录任务、阶段和受控资源引用.
 *
 * <p>契约示例：
 * <pre>{@code
 * {"schemaVersion":1,"taskId":12,"stage":"PARSE_PAPER",
 *  "source":{"fileId":3,"storagePath":"data/papers/3/paper.pdf"}}
 * }</pre>
 */
@JsonIgnoreProperties(ignoreUnknown = true)
public record StageInputSnapshot(
        @JsonProperty("schemaVersion") int schemaVersion,
        @JsonProperty("taskId") Long taskId,
        @JsonProperty("stage") TaskStage stage,
        @JsonProperty("source") StageResourceRef source) {

    public StageInputSnapshot {
        if (schemaVersion != StageSnapshotContract.SCHEMA_VERSION) {
            throw new IllegalArgumentException("unsupported schemaVersion: " + schemaVersion);
        }
        if (taskId == null || stage == null || source == null) {
            throw new IllegalArgumentException("missing required field in stage input snapshot");
        }
    }
}

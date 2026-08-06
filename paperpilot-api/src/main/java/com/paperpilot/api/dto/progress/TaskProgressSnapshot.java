package com.paperpilot.api.dto.progress;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonProperty;

import java.time.Instant;

/**
 * 任务进度快照（Redis 短期缓存，非正式状态真相来源）.
 *
 * <p>只含进度/阶段/消息等小型元数据，不写入论文全文或源码大对象。
 */
@JsonIgnoreProperties(ignoreUnknown = true)
public record TaskProgressSnapshot(
        @JsonProperty("schemaVersion") int schemaVersion,
        @JsonProperty("taskId") Long taskId,
        @JsonProperty("status") String status,
        @JsonProperty("stage") String stage,
        @JsonProperty("progress") Integer progress,
        @JsonProperty("message") String message,
        @JsonProperty("updatedAt") Instant updatedAt) {

    public static final int SCHEMA_VERSION = 1;
}

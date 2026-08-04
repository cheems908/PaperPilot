package com.paperpilot.api.dto.snapshot;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonProperty;

import java.time.Instant;

/**
 * 阶段错误快照：错误码、是否可重试、消息与发生时间.
 *
 * <p>契约示例：
 * <pre>{@code
 * {"schemaVersion":1,"errorCode":"WORKER_TIMEOUT","retryable":true,
 *  "message":"worker request timed out","occurredAt":"2026-08-04T12:00:00Z"}
 * }</pre>
 */
@JsonIgnoreProperties(ignoreUnknown = true)
public record StageErrorSnapshot(
        @JsonProperty("schemaVersion") int schemaVersion,
        @JsonProperty("errorCode") String errorCode,
        @JsonProperty("retryable") boolean retryable,
        @JsonProperty("message") String message,
        @JsonProperty("occurredAt") Instant occurredAt) {

    public StageErrorSnapshot {
        if (schemaVersion != StageSnapshotContract.SCHEMA_VERSION) {
            throw new IllegalArgumentException("unsupported schemaVersion: " + schemaVersion);
        }
        if (errorCode == null || message == null || occurredAt == null) {
            throw new IllegalArgumentException("missing required field in stage error snapshot");
        }
    }
}

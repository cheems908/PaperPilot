package com.paperpilot.api.service;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonProperty;

import java.time.Instant;
import java.util.UUID;

/**
 * 统一任务事件（Redis Pub/Sub 广播 + SSE 推送）.
 *
 * <p>含 schemaVersion / eventId / sequence / taskId / type / occurredAt / payload；
 * 事件类型见 {@link TaskEventType}。payload 为小型进度元数据，不含大对象。
 */
@JsonIgnoreProperties(ignoreUnknown = true)
public record TaskEvent(
        @JsonProperty("schemaVersion") int schemaVersion,
        @JsonProperty("eventId") String eventId,
        @JsonProperty("sequence") long sequence,
        @JsonProperty("taskId") Long taskId,
        @JsonProperty("type") String type,
        @JsonProperty("occurredAt") Instant occurredAt,
        @JsonProperty("payload") Object payload) {

    public static final int SCHEMA_VERSION = 1;

    public static TaskEvent of(long sequence, Long taskId, String type, Object payload) {
        return new TaskEvent(SCHEMA_VERSION, UUID.randomUUID().toString(), sequence,
                taskId, type, Instant.now(), payload);
    }
}

package com.paperpilot.api.dto.snapshot;

import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;

/**
 * 阶段快照契约：统一 {@code schemaVersion} 与 Jackson 编解码.
 *
 * <p>所有阶段快照 JSON 必须携带 {@code schemaVersion}；业务代码不得手拼 JSON，
 * 一律经 {@link StageSnapshotCodec}（或本类 {@link #MAPPER}）序列化。
 * 未知字段向后兼容（忽略），时间用 ISO-8601 字符串（如 {@code 2026-08-04T12:00:00Z}）。
 */
public final class StageSnapshotContract {

    /** 当前快照 schema 版本 */
    public static final int SCHEMA_VERSION = 1;

    /** 统一 ObjectMapper：JSR-310（Instant）+ 忽略未知字段 + 时间不写时间戳 */
    public static final ObjectMapper MAPPER = new ObjectMapper()
            .registerModule(new JavaTimeModule())
            .disable(SerializationFeature.WRITE_DATES_AS_TIMESTAMPS)
            .disable(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES);

    private StageSnapshotContract() {
    }
}

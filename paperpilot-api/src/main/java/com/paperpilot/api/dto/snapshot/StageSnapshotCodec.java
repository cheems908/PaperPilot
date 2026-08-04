package com.paperpilot.api.dto.snapshot;

import com.fasterxml.jackson.core.JsonProcessingException;

/**
 * 阶段快照 JSON 编解码统一入口.
 *
 * <p>业务代码通过本类读写快照 JSON，禁止手拼 JSON。
 * 非法或缺失必要字段在反序列化（服务边界）被拒绝：DTO 构造校验抛
 * {@link IllegalArgumentException}，被 Jackson 包装为 {@link JsonProcessingException} 抛出。
 */
public final class StageSnapshotCodec {

    private StageSnapshotCodec() {
    }

    public static String toJson(Object snapshot) throws JsonProcessingException {
        return StageSnapshotContract.MAPPER.writeValueAsString(snapshot);
    }

    public static <T> T fromJson(String json, Class<T> type) throws JsonProcessingException {
        return StageSnapshotContract.MAPPER.readValue(json, type);
    }
}

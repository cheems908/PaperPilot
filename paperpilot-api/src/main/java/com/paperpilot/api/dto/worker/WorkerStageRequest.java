package com.paperpilot.api.dto.worker;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonProperty;
import com.paperpilot.api.domain.enums.TaskStage;

/**
 * 阶段执行请求：由编排方从阶段消息 + 数据库输入快照组装.
 *
 * <p>携带全链路标识（requestId / taskId / stageExecutionId / attempt）与阶段输入
 * {@code input}；{@code input} 类型随阶段而异（论文引用、仓库引用等），由调用方提供。
 * 构造校验拒绝非法请求，避免把脏请求发给 Worker。
 */
@JsonIgnoreProperties(ignoreUnknown = true)
public record WorkerStageRequest(
        @JsonProperty("schemaVersion") int schemaVersion,
        @JsonProperty("requestId") String requestId,
        @JsonProperty("taskId") Long taskId,
        @JsonProperty("stageExecutionId") Long stageExecutionId,
        @JsonProperty("stage") TaskStage stage,
        @JsonProperty("attempt") Integer attempt,
        @JsonProperty("input") Object input) {

    public static final int SCHEMA_VERSION = 1;

    public WorkerStageRequest {
        if (schemaVersion != SCHEMA_VERSION) {
            throw new IllegalArgumentException("unsupported schemaVersion: " + schemaVersion);
        }
        if (taskId == null || stageExecutionId == null || stage == null
                || attempt == null || attempt <= 0) {
            throw new IllegalArgumentException("missing required field in worker stage request");
        }
    }
}

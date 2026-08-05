package com.paperpilot.api.dto.worker;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonProperty;
import com.paperpilot.api.dto.snapshot.StageArtifactRef;

import java.util.List;
import java.util.Map;

/**
 * 阶段执行响应：校验必填字段；{@code success=true} 时 {@code output} 必须存在.
 *
 * <p>未知字段向后兼容（忽略）；构造校验失败被客户端映射为
 * {@code WorkerErrorCode.INVALID_RESPONSE}。
 */
@JsonIgnoreProperties(ignoreUnknown = true)
public record WorkerStageResponse(
        @JsonProperty("schemaVersion") int schemaVersion,
        @JsonProperty("success") Boolean success,
        @JsonProperty("output") Object output,
        @JsonProperty("artifacts") List<StageArtifactRef> artifacts,
        @JsonProperty("metrics") Map<String, Object> metrics,
        @JsonProperty("workerVersion") String workerVersion) {

    public static final int SCHEMA_VERSION = 1;

    public WorkerStageResponse {
        if (schemaVersion != SCHEMA_VERSION) {
            throw new IllegalArgumentException("unsupported schemaVersion: " + schemaVersion);
        }
        if (success == null) {
            throw new IllegalArgumentException("success 缺失");
        }
        if (Boolean.TRUE.equals(success) && output == null) {
            throw new IllegalArgumentException("success 但 output 缺失");
        }
    }
}

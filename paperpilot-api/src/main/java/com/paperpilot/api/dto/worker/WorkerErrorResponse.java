package com.paperpilot.api.dto.worker;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonProperty;

/**
 * Worker 统一错误响应体（Python 侧 {@code {schemaVersion, success, errorCode, retryable, message}}）.
 *
 * <p>4xx/5xx 响应体解析后，远端 {@code errorCode}/{@code retryable} 透传到
 * {@code WorkerException}，再写入阶段错误快照（而不是笼统的 HTTP_4XX/HTTP_5XX）。
 */
@JsonIgnoreProperties(ignoreUnknown = true)
public record WorkerErrorResponse(
        @JsonProperty("schemaVersion") Integer schemaVersion,
        @JsonProperty("success") Boolean success,
        @JsonProperty("errorCode") String errorCode,
        @JsonProperty("retryable") Boolean retryable,
        @JsonProperty("message") String message) {
}

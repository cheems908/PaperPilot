package com.paperpilot.api.service;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonProperty;

/** 事件载荷：状态/阶段/进度/消息（小型元数据，无全文/源码）. */
@JsonIgnoreProperties(ignoreUnknown = true)
public record TaskEventPayload(
        @JsonProperty("status") String status,
        @JsonProperty("stage") String stage,
        @JsonProperty("progress") Integer progress,
        @JsonProperty("message") String message) {
}

package com.paperpilot.api.dto.mq;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import com.paperpilot.api.domain.enums.TaskStage;
import jakarta.validation.ConstraintViolation;
import jakarta.validation.Validation;
import jakarta.validation.Validator;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;

/**
 * RocketMQ 单阶段任务消息契约（{@code schemaVersion}=1）.
 *
 * <p>只携带标识符与调度元数据（{@code taskId} / {@code stageExecutionId} /
 * {@code stage} / {@code attempt}）；阶段 input 由消费方从数据库 snapshot 加载，
 * 消息中不得出现 PDF 全文、源码全集或完整分析结果。
 * {@code requestId} 沿用 HTTP 链路标识（T1.4-04），由生产方从 {@code RequestId.current()} 携带。
 *
 * <p>反序列化边界：
 * <ul>
 *   <li>未知字段忽略（旧消费者兼容新增字段，见 {@link #MAPPER}）；</li>
 *   <li>未知/非法枚举值由 Jackson 以 {@code InvalidFormatException} 拒绝（可观察错误）；</li>
 *   <li>必填字段缺失、{@code attempt} ≤ 0、非 MVP stage、未知 {@code schemaVersion}
 *       由 {@link #validate()} 以 {@link IllegalArgumentException} 拒绝。</li>
 * </ul>
 */
public record StageTaskMessage(
        int schemaVersion,
        @NotBlank String messageId,
        String requestId,
        @NotNull Long taskId,
        @NotNull Long stageExecutionId,
        @NotNull TaskStage stage,
        @NotNull @Positive Integer attempt,
        Instant createdAt) {

    /** 当前消息契约版本 */
    public static final int SCHEMA_VERSION = 1;

    private static final Validator VALIDATOR = Validation.buildDefaultValidatorFactory().getValidator();

    /** 消息 JSON 编解码器：JSR-310 + 忽略未知字段 + 时间不写时间戳；未知枚举值保持默认报错。 */
    public static final ObjectMapper MAPPER = new ObjectMapper()
            .registerModule(new JavaTimeModule())
            .disable(SerializationFeature.WRITE_DATES_AS_TIMESTAMPS)
            .disable(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES);

    public static String toJson(StageTaskMessage message) throws JsonProcessingException {
        return MAPPER.writeValueAsString(message);
    }

    /** 反序列化并做契约校验；解析或校验失败均抛出带原因的异常（可观察错误）。 */
    public static StageTaskMessage fromJson(String json) throws JsonProcessingException {
        return MAPPER.readValue(json, StageTaskMessage.class).validate();
    }

    /** 契约校验；非法则抛 {@link IllegalArgumentException}（列出全部违规），合法返回自身。 */
    public StageTaskMessage validate() {
        List<String> problems = new ArrayList<>();
        for (ConstraintViolation<StageTaskMessage> violation : VALIDATOR.validate(this)) {
            problems.add(violation.getPropertyPath() + " " + violation.getMessage());
        }
        if (schemaVersion != SCHEMA_VERSION) {
            problems.add("unsupported schemaVersion " + schemaVersion + " (expect " + SCHEMA_VERSION + ")");
        }
        if (stage != null && !TaskStage.MVP_STAGES.contains(stage)) {
            problems.add("stage must be one of MVP stages " + TaskStage.MVP_STAGES + " but was " + stage);
        }
        if (!problems.isEmpty()) {
            throw new IllegalArgumentException("invalid StageTaskMessage: " + String.join("; ", problems));
        }
        return this;
    }
}

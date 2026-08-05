package com.paperpilot.api;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.paperpilot.api.domain.enums.TaskStage;
import com.paperpilot.api.dto.mq.StageTaskMessage;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.Arrays;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * 阶段消息契约测试：round-trip 保真、未知字段向后兼容、必填/attempt/枚举/MVP/schemaVersion 校验.
 */
class StageTaskMessageTest {

    @Test
    void validMessageRoundTripsUnchanged() throws Exception {
        StageTaskMessage original = message(12L, 34L, TaskStage.PARSE_PAPER, 1);

        String json = StageTaskMessage.toJson(original);
        StageTaskMessage parsed = StageTaskMessage.fromJson(json);

        assertThat(parsed).isEqualTo(original);
        assertThat(parsed.schemaVersion()).isEqualTo(StageTaskMessage.SCHEMA_VERSION);
        assertThat(parsed.requestId()).isEqualTo("req-1");
        assertThat(parsed.createdAt()).isEqualTo(Instant.parse("2026-08-04T12:00:00Z"));
    }

    @Test
    void unknownFieldsDoNotBreakDeserialization() throws Exception {
        String json = "{\"schemaVersion\":1,\"messageId\":\"m-1\",\"requestId\":\"req-1\","
                + "\"taskId\":12,\"stageExecutionId\":34,\"stage\":\"PARSE_PAPER\","
                + "\"attempt\":1,\"createdAt\":\"2026-08-04T12:00:00Z\","
                + "\"futureField\":{\"nested\":true},\"another\":\"x\"}";

        StageTaskMessage parsed = StageTaskMessage.fromJson(json);

        assertThat(parsed.taskId()).isEqualTo(12L);
        assertThat(parsed.stageExecutionId()).isEqualTo(34L);
        assertThat(parsed.stage()).isEqualTo(TaskStage.PARSE_PAPER);
        assertThat(parsed.attempt()).isEqualTo(1);
    }

    @Test
    void missingRequiredFieldsAreRejected() {
        assertThatThrownBy(() -> StageTaskMessage.fromJson(without("taskId")))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("taskId");
        assertThatThrownBy(() -> StageTaskMessage.fromJson(without("stageExecutionId")))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("stageExecutionId");
        assertThatThrownBy(() -> StageTaskMessage.fromJson(without("stage")))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("stage");
        assertThatThrownBy(() -> StageTaskMessage.fromJson(without("attempt")))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("attempt");
    }

    @Test
    void zeroOrNegativeAttemptIsRejected() {
        assertThatThrownBy(() -> message(12L, 34L, TaskStage.PARSE_PAPER, 0).validate())
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("attempt");
        assertThatThrownBy(() -> message(12L, 34L, TaskStage.PARSE_PAPER, -3).validate())
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("attempt");
    }

    @Test
    void unknownEnumValueIsRejectedAsObservableError() {
        String json = "{\"schemaVersion\":1,\"messageId\":\"m-1\",\"requestId\":\"req-1\","
                + "\"taskId\":12,\"stageExecutionId\":34,\"stage\":\"NO_SUCH_STAGE\","
                + "\"attempt\":1,\"createdAt\":\"2026-08-04T12:00:00Z\"}";
        assertThatThrownBy(() -> StageTaskMessage.fromJson(json))
                .isInstanceOf(JsonProcessingException.class)
                .hasMessageContaining("NO_SUCH_STAGE");
    }

    @Test
    void nonMvpStageIsRejected() {
        assertThatThrownBy(() -> message(12L, 34L, TaskStage.GENERATE_REPORT, 1).validate())
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("MVP");
    }

    @Test
    void unknownSchemaVersionIsRejected() {
        String json = "{\"schemaVersion\":99,\"messageId\":\"m-1\",\"requestId\":\"req-1\","
                + "\"taskId\":12,\"stageExecutionId\":34,\"stage\":\"PARSE_PAPER\","
                + "\"attempt\":1,\"createdAt\":\"2026-08-04T12:00:00Z\"}";
        assertThatThrownBy(() -> StageTaskMessage.fromJson(json))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("schemaVersion");
    }

    // ── helpers ────────────────────────────────────────────────────────────

    private StageTaskMessage message(long taskId, long stageExecutionId, TaskStage stage, int attempt) {
        return new StageTaskMessage(StageTaskMessage.SCHEMA_VERSION, "m-1", "req-1",
                taskId, stageExecutionId, stage, attempt, Instant.parse("2026-08-04T12:00:00Z"));
    }

    /** 从合法 JSON 中去掉指定字段（用于“必填缺失”用例），保持 JSON 语法合法。 */
    private String without(String field) {
        String base = "{\"schemaVersion\":1,\"messageId\":\"m-1\",\"requestId\":\"req-1\","
                + "\"taskId\":12,\"stageExecutionId\":34,\"stage\":\"PARSE_PAPER\","
                + "\"attempt\":1,\"createdAt\":\"2026-08-04T12:00:00Z\"}";
        String target = Arrays.stream(new String[]{
                        "\"taskId\":12,",
                        "\"stageExecutionId\":34,",
                        "\"stage\":\"PARSE_PAPER\",",
                        "\"attempt\":1,",
                })
                .filter(seg -> seg.startsWith("\"" + field + "\""))
                .findFirst()
                .orElseThrow(() -> new IllegalArgumentException("unknown field: " + field));
        return base.replace(target, "");
    }
}

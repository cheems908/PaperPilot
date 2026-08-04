package com.paperpilot.api;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.paperpilot.api.domain.enums.TaskStage;
import com.paperpilot.api.dto.snapshot.StageArtifactRef;
import com.paperpilot.api.dto.snapshot.StageErrorSnapshot;
import com.paperpilot.api.dto.snapshot.StageInputSnapshot;
import com.paperpilot.api.dto.snapshot.StageOutputSnapshot;
import com.paperpilot.api.dto.snapshot.StageResourceRef;
import com.paperpilot.api.dto.snapshot.StageSnapshotCodec;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * 阶段快照契约测试：字段名与契约一致、round-trip、
 * 未知字段可忽略、非法或缺失必要字段在服务边界被拒.
 */
class StageSnapshotTest {

    // ── 序列化字段名与契约示例一致 ─────────────────────────────────────────

    @Test
    void inputSnapshotSerializesToContractShape() throws Exception {
        String json = StageSnapshotCodec.toJson(new StageInputSnapshot(
                1, 12L, TaskStage.PARSE_PAPER,
                new StageResourceRef(3L, "data/papers/3/paper.pdf")));

        assertThat(json)
                .contains("\"schemaVersion\":1")
                .contains("\"taskId\":12")
                .contains("\"stage\":\"PARSE_PAPER\"")
                .contains("\"fileId\":3")
                .contains("\"storagePath\":\"data/papers/3/paper.pdf\"");
    }

    @Test
    void errorSnapshotSerializesToContractShape() throws Exception {
        String json = StageSnapshotCodec.toJson(new StageErrorSnapshot(
                1, "WORKER_TIMEOUT", true, "worker request timed out",
                Instant.parse("2026-08-04T12:00:00Z")));

        assertThat(json)
                .contains("\"schemaVersion\":1")
                .contains("\"errorCode\":\"WORKER_TIMEOUT\"")
                .contains("\"retryable\":true")
                .contains("\"message\":\"worker request timed out\"")
                .contains("\"occurredAt\":\"2026-08-04T12:00:00Z\"");
    }

    @Test
    void outputSnapshotSerializesToContractShape() throws Exception {
        String json = StageSnapshotCodec.toJson(new StageOutputSnapshot(
                1, "0.1.0",
                List.of(new StageArtifactRef("summary", "data/papers/3/summary.json")),
                Map.of("sectionCount", 12, "conceptCount", 8)));

        assertThat(json)
                .contains("\"schemaVersion\":1")
                .contains("\"workerVersion\":\"0.1.0\"")
                .contains("\"artifactRefs\"")
                .contains("\"sectionCount\":12")
                .contains("\"conceptCount\":8");
    }

    // ── round-trip ────────────────────────────────────────────────────────

    @Test
    void inputSnapshotRoundTrips() throws Exception {
        StageInputSnapshot original = new StageInputSnapshot(
                1, 12L, TaskStage.PARSE_PAPER,
                new StageResourceRef(3L, "data/papers/3/paper.pdf"));
        assertThat(roundTrip(original, StageInputSnapshot.class)).isEqualTo(original);
    }

    @Test
    void outputSnapshotRoundTrips() throws Exception {
        StageOutputSnapshot original = new StageOutputSnapshot(
                1, "0.1.0",
                List.of(new StageArtifactRef("summary", "data/papers/3/summary.json")),
                Map.of("sectionCount", 12, "conceptCount", 8));
        assertThat(roundTrip(original, StageOutputSnapshot.class)).isEqualTo(original);
    }

    @Test
    void errorSnapshotRoundTripsWithInstant() throws Exception {
        StageErrorSnapshot original = new StageErrorSnapshot(
                1, "WORKER_TIMEOUT", true, "worker request timed out",
                Instant.parse("2026-08-04T12:00:00Z"));
        assertThat(roundTrip(original, StageErrorSnapshot.class)).isEqualTo(original);
    }

    // ── 未知字段向后兼容 ──────────────────────────────────────────────────

    @Test
    void unknownFieldsAreIgnoredOnDeserialize() throws Exception {
        String json = "{\"schemaVersion\":1,\"taskId\":12,\"stage\":\"PARSE_PAPER\","
                + "\"source\":{\"fileId\":3,\"storagePath\":\"data/papers/3/paper.pdf\",\"extra\":1},"
                + "\"futureField\":\"ignored\"}";

        StageInputSnapshot parsed = StageSnapshotCodec.fromJson(json, StageInputSnapshot.class);
        assertThat(parsed.taskId()).isEqualTo(12L);
        assertThat(parsed.stage()).isEqualTo(TaskStage.PARSE_PAPER);
        assertThat(parsed.source().fileId()).isEqualTo(3L);
        assertThat(parsed.source().storagePath()).isEqualTo("data/papers/3/paper.pdf");
    }

    // ── 非法 / 缺失必要字段在服务边界被拒 ─────────────────────────────────

    @Test
    void rejectsUnsupportedSchemaVersion() {
        assertThatThrownBy(() -> StageSnapshotCodec.fromJson(
                "{\"schemaVersion\":2,\"taskId\":12,\"stage\":\"PARSE_PAPER\","
                        + "\"source\":{\"fileId\":3,\"storagePath\":\"p\"}}",
                StageInputSnapshot.class))
                .isInstanceOf(JsonProcessingException.class)
                .hasRootCauseInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void rejectsInputMissingRequiredFields() {
        // 缺 source
        assertThatThrownBy(() -> StageSnapshotCodec.fromJson(
                "{\"schemaVersion\":1,\"taskId\":12,\"stage\":\"PARSE_PAPER\"}",
                StageInputSnapshot.class))
                .isInstanceOf(JsonProcessingException.class);
        // 缺 taskId
        assertThatThrownBy(() -> StageSnapshotCodec.fromJson(
                "{\"schemaVersion\":1,\"stage\":\"PARSE_PAPER\","
                        + "\"source\":{\"fileId\":3,\"storagePath\":\"p\"}}",
                StageInputSnapshot.class))
                .isInstanceOf(JsonProcessingException.class);
    }

    @Test
    void rejectsOutputMissingWorkerVersion() {
        assertThatThrownBy(() -> StageSnapshotCodec.fromJson(
                "{\"schemaVersion\":1,\"artifactRefs\":[],\"summary\":{}}",
                StageOutputSnapshot.class))
                .isInstanceOf(JsonProcessingException.class);
    }

    @Test
    void rejectsErrorMissingRequiredFields() {
        // 缺 occurredAt
        assertThatThrownBy(() -> StageSnapshotCodec.fromJson(
                "{\"schemaVersion\":1,\"errorCode\":\"WORKER_TIMEOUT\",\"retryable\":true,"
                        + "\"message\":\"timed out\"}",
                StageErrorSnapshot.class))
                .isInstanceOf(JsonProcessingException.class);
    }

    @Test
    void outputSnapshotDefaultsNullCollectionsToEmpty() {
        StageOutputSnapshot snapshot = new StageOutputSnapshot(1, "0.1.0", null, null);
        assertThat(snapshot.artifactRefs()).isEmpty();
        assertThat(snapshot.summary()).isEmpty();
    }

    private static <T> T roundTrip(T snapshot, Class<T> type) throws Exception {
        return StageSnapshotCodec.fromJson(StageSnapshotCodec.toJson(snapshot), type);
    }
}

package com.paperpilot.api;

import com.paperpilot.api.domain.entity.AnalysisTask;
import com.paperpilot.api.domain.entity.Project;
import com.paperpilot.api.domain.entity.StageExecution;
import com.paperpilot.api.domain.enums.StageExecutionStatus;
import com.paperpilot.api.domain.enums.TaskStage;
import com.paperpilot.api.dto.snapshot.StageArtifactRef;
import com.paperpilot.api.dto.snapshot.StageErrorSnapshot;
import com.paperpilot.api.dto.snapshot.StageInputSnapshot;
import com.paperpilot.api.dto.snapshot.StageOutputSnapshot;
import com.paperpilot.api.dto.snapshot.StageResourceRef;
import com.paperpilot.api.dto.snapshot.StageSnapshotCodec;
import com.paperpilot.api.mapper.AnalysisTaskMapper;
import com.paperpilot.api.mapper.ProjectMapper;
import com.paperpilot.api.mapper.StageExecutionMapper;
import org.apache.ibatis.session.SqlSession;
import org.apache.ibatis.session.SqlSessionFactory;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.Test;
import org.testcontainers.containers.MySQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import javax.sql.DataSource;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.ResultSet;
import java.sql.Statement;
import java.time.Instant;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * StageExecution 新快照/时间列在真实 MySQL 上的持久化与回读，
 * 验证实体与 V3 迁移一致、枚举按 name() 落库、快照 JSON 可被 DTO 反序列化.
 */
@Testcontainers
class StageExecutionPersistenceTest {

    @Container
    static final MySQLContainer<?> MYSQL = new MySQLContainer<>("mysql:8.0");

    @Test
    void persistsSnapshotContractsAndStatusEnum() throws Exception {
        DataSource ds = TestSupport.dataSource(MYSQL);
        Flyway.configure().dataSource(ds).load().migrate();
        SqlSessionFactory factory = TestSupport.buildFactory(ds);

        try (SqlSession session = factory.openSession(true)) {
            ProjectMapper projectMapper = session.getMapper(ProjectMapper.class);
            AnalysisTaskMapper taskMapper = session.getMapper(AnalysisTaskMapper.class);
            StageExecutionMapper stageMapper = session.getMapper(StageExecutionMapper.class);

            Project project = new Project();
            project.setName("snapshot-contract");
            projectMapper.insert(project);

            AnalysisTask task = new AnalysisTask();
            task.setProjectId(project.getId());
            task.setRequestKey("req-stage-snapshot");
            taskMapper.insert(task);

            StageExecution execution = new StageExecution();
            execution.setTaskId(task.getId());
            execution.setStage(TaskStage.PARSE_PAPER);
            execution.setAttempt(1);
            execution.setStatus(StageExecutionStatus.RUNNING);
            execution.setInputSnapshot(StageSnapshotCodec.toJson(new StageInputSnapshot(
                    1, task.getId(), TaskStage.PARSE_PAPER,
                    new StageResourceRef(3L, "data/papers/3/paper.pdf"))));
            execution.setOutputSnapshot(StageSnapshotCodec.toJson(new StageOutputSnapshot(
                    1, "0.1.0",
                    List.of(new StageArtifactRef("summary", "data/papers/3/summary.json")),
                    Map.of("sectionCount", 12))));
            execution.setErrorSnapshot(StageSnapshotCodec.toJson(new StageErrorSnapshot(
                    1, "WORKER_TIMEOUT", true, "worker request timed out",
                    Instant.parse("2026-08-04T12:00:00Z"))));
            execution.setStartedAt(LocalDateTime.of(2026, 8, 4, 12, 0, 0));
            execution.setFinishedAt(LocalDateTime.of(2026, 8, 4, 12, 0, 30));
            execution.setNextRetryAt(LocalDateTime.of(2026, 8, 4, 12, 5, 0));
            execution.setHeartbeatAt(LocalDateTime.of(2026, 8, 4, 12, 0, 15));
            stageMapper.insert(execution);

            StageExecution loaded = stageMapper.selectById(execution.getId());
            assertThat(loaded.getStatus()).isEqualTo(StageExecutionStatus.RUNNING);
            assertThat(loaded.getInputSnapshot()).isNotNull();
            assertThat(loaded.getOutputSnapshot()).isNotNull();
            assertThat(loaded.getErrorSnapshot()).isNotNull();
            assertThat(loaded.getStartedAt()).isEqualTo(execution.getStartedAt());
            assertThat(loaded.getFinishedAt()).isEqualTo(execution.getFinishedAt());
            assertThat(loaded.getNextRetryAt()).isEqualTo(execution.getNextRetryAt());
            assertThat(loaded.getHeartbeatAt()).isEqualTo(execution.getHeartbeatAt());

            // 快照 JSON 可被 DTO 反序列化（契约可用）
            StageInputSnapshot input = StageSnapshotCodec.fromJson(loaded.getInputSnapshot(), StageInputSnapshot.class);
            assertThat(input.taskId()).isEqualTo(task.getId());
            assertThat(input.stage()).isEqualTo(TaskStage.PARSE_PAPER);
            assertThat(input.source().fileId()).isEqualTo(3L);

            // 枚举按 name() 落库、时间列按 DATETIME 落库
            try (Connection conn = openConnection(); Statement stmt = conn.createStatement();
                 ResultSet rs = stmt.executeQuery(
                         "SELECT status, started_at, heartbeat_at FROM stage_execution WHERE id = " + loaded.getId())) {
                assertThat(rs.next()).isTrue();
                assertThat(rs.getString("status")).isEqualTo("RUNNING");
                assertThat(rs.getTimestamp("started_at").toLocalDateTime()).isEqualTo(execution.getStartedAt());
                assertThat(rs.getTimestamp("heartbeat_at").toLocalDateTime()).isEqualTo(execution.getHeartbeatAt());
            }
        }
    }

    private Connection openConnection() throws Exception {
        return DriverManager.getConnection(MYSQL.getJdbcUrl(), MYSQL.getUsername(), MYSQL.getPassword());
    }
}

package com.paperpilot.api;

import org.flywaydb.core.Flyway;
import org.flywaydb.core.api.MigrationVersion;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.testcontainers.containers.MySQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.ResultSet;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * V3 迁移测试：在空库与已执行 V1/V2 的库上都能成功迁移，
 * 并保留旧 snapshot / error_message 列及数据.
 */
@Testcontainers
class StageExecutionMigrationTest {

    @Container
    static final MySQLContainer<?> MYSQL = new MySQLContainer<>("mysql:8.0");

    /** 每个测试前清空 schema，保证从同一基线出发（含 V1/V2 目标测试） */
    @BeforeEach
    void resetDatabase() {
        Flyway.configure()
                .dataSource(MYSQL.getJdbcUrl(), MYSQL.getUsername(), MYSQL.getPassword())
                .cleanDisabled(false)
                .load()
                .clean();
    }

    @Test
    void v3MigratesOnEmptyDatabase() throws Exception {
        migrateLatest();

        assertThat(stageExecutionColumns())
                .contains("input_snapshot", "output_snapshot", "error_snapshot",
                        "started_at", "finished_at", "next_retry_at", "heartbeat_at")
                .contains("snapshot", "error_message");
        assertThat(stageExecutionIndexes())
                .contains("idx_stage_task_status", "idx_stage_status", "idx_stage_next_retry");
    }

    @Test
    void v3MigratesOnTopOfV1V2() throws Exception {
        // 先只迁移到 V2
        Flyway.configure()
                .dataSource(MYSQL.getJdbcUrl(), MYSQL.getUsername(), MYSQL.getPassword())
                .target(MigrationVersion.fromVersion("2"))
                .load()
                .migrate();

        assertThat(stageExecutionColumns())
                .doesNotContain("input_snapshot", "output_snapshot", "error_snapshot",
                        "started_at", "finished_at", "next_retry_at", "heartbeat_at");

        // 再迁移到最新（V3）
        migrateLatest();

        assertThat(stageExecutionColumns())
                .contains("input_snapshot", "output_snapshot", "error_snapshot",
                        "started_at", "finished_at", "next_retry_at", "heartbeat_at")
                .contains("snapshot", "error_message");
    }

    @Test
    void v3KeepsLegacyColumnsAndDataIntact() throws Exception {
        // 迁移到 V2 并写入使用旧列的数据
        Flyway.configure()
                .dataSource(MYSQL.getJdbcUrl(), MYSQL.getUsername(), MYSQL.getPassword())
                .target(MigrationVersion.fromVersion("2"))
                .load()
                .migrate();
        long projectId;
        long taskId;
        long stageId;
        try (Connection conn = openConnection(); Statement stmt = conn.createStatement()) {
            stmt.executeUpdate("INSERT INTO project (name) VALUES ('legacy')");
            projectId = lastId(stmt);
            stmt.executeUpdate("INSERT INTO analysis_task (project_id, request_key, status) "
                    + "VALUES (" + projectId + ", 'req-legacy', 'RUNNING')");
            taskId = lastId(stmt);
            stmt.executeUpdate("INSERT INTO stage_execution (task_id, stage, attempt, status, snapshot, error_message) "
                    + "VALUES (" + taskId + ", 'PARSE_PAPER', 1, 'PENDING', '{\"legacy\":true}', 'boom')");
            stageId = lastId(stmt);
        }

        // 升级到 V3
        migrateLatest();

        try (Connection conn = openConnection(); Statement stmt = conn.createStatement();
             ResultSet rs = stmt.executeQuery(
                     "SELECT status, snapshot, error_message, input_snapshot FROM stage_execution WHERE id = " + stageId)) {
            assertThat(rs.next()).isTrue();
            assertThat(rs.getString("status")).isEqualTo("PENDING");
            assertThat(rs.getString("snapshot")).isEqualTo("{\"legacy\":true}");
            assertThat(rs.getString("error_message")).isEqualTo("boom");
            assertThat(rs.getString("input_snapshot")).isNull();
        }
    }

    @Test
    void v8BackfillsLegacyConceptIdentityAndKeepsHistory() throws Exception {
        Flyway.configure()
                .dataSource(MYSQL.getJdbcUrl(), MYSQL.getUsername(), MYSQL.getPassword())
                .target(MigrationVersion.fromVersion("7"))
                .load()
                .migrate();
        long conceptId;
        try (Connection conn = openConnection(); Statement stmt = conn.createStatement()) {
            stmt.executeUpdate("INSERT INTO project (name) VALUES ('legacy-concept')");
            long projectId = lastId(stmt);
            stmt.executeUpdate("INSERT INTO paper (project_id, title, pdf_url) VALUES ("
                    + projectId + ", 'Paper', 'legacy.pdf')");
            long paperId = lastId(stmt);
            stmt.executeUpdate("INSERT INTO paper_concept (paper_id, concept_name, evidence_text) "
                    + "VALUES (" + paperId + ", 'legacy term', 'legacy evidence')");
            conceptId = lastId(stmt);
        }

        migrateLatest();

        try (Connection conn = openConnection(); Statement stmt = conn.createStatement();
             ResultSet rs = stmt.executeQuery("SELECT concept_key, extractor_version, decision, evidence_text "
                     + "FROM paper_concept WHERE id=" + conceptId)) {
            assertThat(rs.next()).isTrue();
            assertThat(rs.getString("concept_key")).startsWith("legacy_").hasSize(27);
            assertThat(rs.getString("extractor_version")).isEqualTo("legacy-v1");
            assertThat(rs.getString("decision")).isEqualTo("MAPPED");
            assertThat(rs.getString("evidence_text")).isEqualTo("legacy evidence");
        }
        try (Connection conn = openConnection(); Statement stmt = conn.createStatement()) {
            assertThat(queryStrings(stmt, "SELECT index_name FROM information_schema.statistics "
                    + "WHERE table_schema=DATABASE() AND table_name='paper_concept'"))
                    .contains("uk_concept_paper_key").doesNotContain("uk_concept_paper_name");
        }
    }

    private void migrateLatest() {
        Flyway.configure()
                .dataSource(MYSQL.getJdbcUrl(), MYSQL.getUsername(), MYSQL.getPassword())
                .load()
                .migrate();
    }

    private List<String> stageExecutionColumns() throws Exception {
        try (Connection conn = openConnection(); Statement stmt = conn.createStatement()) {
            return queryStrings(stmt,
                    "SELECT column_name FROM information_schema.columns "
                            + "WHERE table_schema = DATABASE() AND table_name = 'stage_execution'");
        }
    }

    private List<String> stageExecutionIndexes() throws Exception {
        try (Connection conn = openConnection(); Statement stmt = conn.createStatement()) {
            return queryStrings(stmt,
                    "SELECT index_name FROM information_schema.statistics "
                            + "WHERE table_schema = DATABASE() AND table_name = 'stage_execution' "
                            + "AND index_name <> 'PRIMARY'");
        }
    }

    private Connection openConnection() throws Exception {
        return DriverManager.getConnection(MYSQL.getJdbcUrl(), MYSQL.getUsername(), MYSQL.getPassword());
    }

    private static long lastId(Statement stmt) throws Exception {
        try (ResultSet rs = stmt.executeQuery("SELECT LAST_INSERT_ID() AS id")) {
            rs.next();
            return rs.getLong("id");
        }
    }

    private static List<String> queryStrings(Statement stmt, String sql) throws Exception {
        List<String> result = new ArrayList<>();
        try (ResultSet rs = stmt.executeQuery(sql)) {
            while (rs.next()) {
                result.add(rs.getString(1));
            }
        }
        return result;
    }
}

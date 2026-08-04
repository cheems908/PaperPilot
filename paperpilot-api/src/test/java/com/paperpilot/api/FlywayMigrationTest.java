package com.paperpilot.api;

import org.flywaydb.core.Flyway;
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
 * 用 Testcontainers 起真实 MySQL，跑 Flyway V1 迁移，校验 schema 结构与约束.
 */
@Testcontainers
class FlywayMigrationTest {

    @Container
    static final MySQLContainer<?> MYSQL = new MySQLContainer<>("mysql:8.0");

    @Test
    void migrationCreatesAllTables() throws Exception {
        migrate();

        try (Connection conn = openConnection(); Statement stmt = conn.createStatement()) {
            List<String> tables = queryStrings(stmt,
                    "SELECT table_name FROM information_schema.tables "
                            + "WHERE table_schema = DATABASE() AND table_name <> 'flyway_schema_history' "
                            + "ORDER BY table_name");
            assertThat(tables).containsExactlyInAnyOrder(
                    "project", "paper", "repository", "analysis_task",
                    "stage_execution", "paper_concept", "code_symbol", "concept_code_mapping", "file");
        }
    }

    @Test
    void everyTableHasVersionColumn() throws Exception {
        migrate();

        try (Connection conn = openConnection(); Statement stmt = conn.createStatement()) {
            for (String table : new String[]{"project", "paper", "repository", "analysis_task",
                    "stage_execution", "paper_concept", "code_symbol", "concept_code_mapping", "file"}) {
                List<String> cols = queryStrings(stmt,
                        "SELECT column_name FROM information_schema.columns "
                                + "WHERE table_schema = DATABASE() AND table_name = '" + table + "'");
                assertThat(cols).as("table %s should have id/created_at/updated_at/version", table)
                        .contains("id", "created_at", "updated_at", "version");
            }
        }
    }

    @Test
    void uniqueConstraintsAreEnforced() throws Exception {
        migrate();

        try (Connection conn = openConnection(); Statement stmt = conn.createStatement()) {
            // information_schema.statistics 中 NON_UNIQUE=0 即唯一索引/约束
            List<String> uniqueIndexes = queryStrings(stmt,
                    "SELECT index_name FROM information_schema.statistics "
                            + "WHERE table_schema = DATABASE() AND NON_UNIQUE = 0 AND index_name <> 'PRIMARY'");
            assertThat(uniqueIndexes)
                    .contains("uk_task_request_key", "uk_stage_task_stage_attempt", "uk_code_symbol");
        }
    }

    @Test
    void duplicateRequestKeyIsRejected() throws Exception {
        migrate();

        try (Connection conn = openConnection(); Statement stmt = conn.createStatement()) {
            // 先建一个最小 project（分析任务的外键）
            stmt.executeUpdate("INSERT INTO project (name) VALUES ('p')");
            long projectId;
            try (ResultSet rs = stmt.executeQuery("SELECT LAST_INSERT_ID() AS id")) {
                rs.next();
                projectId = rs.getLong("id");
            }
            String insert = "INSERT INTO analysis_task (project_id, request_key, status) VALUES ("
                    + projectId + ", 'req-1', 'PENDING')";
            stmt.executeUpdate(insert);
            org.assertj.core.api.Assertions.assertThatThrownBy(() -> stmt.executeUpdate(insert))
                    .isInstanceOf(java.sql.SQLIntegrityConstraintViolationException.class);
        }
    }

    private void migrate() {
        Flyway.configure()
                .dataSource(MYSQL.getJdbcUrl(), MYSQL.getUsername(), MYSQL.getPassword())
                .load()
                .migrate();
    }

    private Connection openConnection() throws Exception {
        return DriverManager.getConnection(MYSQL.getJdbcUrl(), MYSQL.getUsername(), MYSQL.getPassword());
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

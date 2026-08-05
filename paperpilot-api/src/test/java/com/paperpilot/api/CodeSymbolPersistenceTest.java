package com.paperpilot.api;

import com.paperpilot.api.domain.entity.CodeSymbol;
import com.paperpilot.api.domain.entity.GitRepository;
import com.paperpilot.api.domain.entity.Project;
import com.paperpilot.api.dto.worker.WorkerStageResponse;
import com.paperpilot.api.mapper.CodeSymbolMapper;
import com.paperpilot.api.mapper.GitRepositoryMapper;
import com.paperpilot.api.mapper.ProjectMapper;
import com.paperpilot.api.service.CodeSymbolPersistenceService;
import org.apache.ibatis.session.SqlSession;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.Test;
import org.testcontainers.containers.MySQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import javax.sql.DataSource;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * INDEX_CODE 结果持久化：按稳定键幂等 upsert 到 code_symbol，重复执行不产生重复记录.
 */
@Testcontainers
class CodeSymbolPersistenceTest {

    @Container
    static final MySQLContainer<?> MYSQL = new MySQLContainer<>("mysql:8.0");

    @Test
    void repeatedPersistDoesNotDuplicateSymbols() throws Exception {
        DataSource ds = TestSupport.dataSource(MYSQL);
        Flyway.configure().dataSource(ds).load().migrate();

        try (SqlSession session = TestSupport.buildFactory(ds).openSession(true)) {
            ProjectMapper projectMapper = session.getMapper(ProjectMapper.class);
            GitRepositoryMapper repositoryMapper = session.getMapper(GitRepositoryMapper.class);
            CodeSymbolMapper codeSymbolMapper = session.getMapper(CodeSymbolMapper.class);
            CodeSymbolPersistenceService service = new CodeSymbolPersistenceService(codeSymbolMapper);

            Project project = new Project();
            project.setName("p");
            projectMapper.insert(project);

            GitRepository repo = new GitRepository();
            repo.setProjectId(project.getId());
            repo.setGithubUrl("https://github.com/paperpilot/patchtst");
            repositoryMapper.insert(repo);

            WorkerStageResponse response = indexResponse();

            // 重复执行同一 commit：第一次 upsert，第二次应复用不产生重复
            String summary = service.persist(repo.getId(), response);
            service.persist(repo.getId(), response);

            assertThat(codeSymbolMapper.selectCount(null)).isEqualTo(3);
            assertThat(summary).contains("\"symbolCount\":3").contains("\"fileCount\":2");

            CodeSymbol row = codeSymbolMapper.selectOne(new com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper<CodeSymbol>()
                    .eq(CodeSymbol::getSymbolName, "PatchTST.forward"));
            assertThat(row).isNotNull();
            assertThat(row.getRepositoryId()).isEqualTo(repo.getId());
            assertThat(row.getCommitSha()).isEqualTo("a".repeat(40));
            assertThat(row.getFilePath()).isEqualTo("model.py");
            assertThat(row.getSymbolType()).isEqualTo("method");
            assertThat(row.getLineNumber()).isEqualTo(12);
        }
    }

    private WorkerStageResponse indexResponse() {
        Map<String, Object> output = new java.util.LinkedHashMap<>();
        output.put("repo", "https://github.com/paperpilot/patchtst");
        output.put("commitSha", "a".repeat(40));
        output.put("files", List.of(
                Map.of("path", "model.py", "symbols", List.of(
                        symbol("class", "PatchTST", "PatchTST", "class PatchTST", 5, 25, null),
                        symbol("method", "forward", "PatchTST.forward", "def forward(self, x)", 12, 15, "PatchTST"))),
                Map.of("path", "utils.py", "symbols", List.of(
                        symbol("function", "set_seed", "set_seed", "def set_seed(seed)", 1, 4, null)))));
        output.put("warnings", List.of("SYNTAX_ERROR: bad.py:1"));
        output.put("stats", Map.of("fileCount", 2, "symbolCount", 3, "warningCount", 1));
        return new WorkerStageResponse(WorkerStageResponse.SCHEMA_VERSION, true,
                output, List.of(), Map.of(), "0.3.0-index");
    }

    /** parent 可能为 null，故用 LinkedHashMap（Map.of 不接受 null 值）。 */
    private Map<String, Object> symbol(String kind, String name, String qualifiedName, String signature,
                                       int startLine, int endLine, String parent) {
        Map<String, Object> m = new java.util.LinkedHashMap<>();
        m.put("kind", kind);
        m.put("name", name);
        m.put("qualifiedName", qualifiedName);
        m.put("signature", signature);
        m.put("startLine", startLine);
        m.put("endLine", endLine);
        m.put("parent", parent);
        return m;
    }
}

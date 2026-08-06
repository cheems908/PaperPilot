package com.paperpilot.api;

import com.paperpilot.api.common.ApiException;
import com.paperpilot.api.domain.entity.AnalysisTask;
import com.paperpilot.api.domain.entity.CodeSymbol;
import com.paperpilot.api.domain.entity.ConceptCodeMapping;
import com.paperpilot.api.domain.entity.GitRepository;
import com.paperpilot.api.domain.entity.Paper;
import com.paperpilot.api.domain.entity.PaperConcept;
import com.paperpilot.api.domain.entity.Project;
import com.paperpilot.api.domain.entity.StageExecution;
import com.paperpilot.api.domain.enums.StageExecutionStatus;
import com.paperpilot.api.domain.enums.TaskStage;
import com.paperpilot.api.domain.enums.TaskStatus;
import com.paperpilot.api.dto.project.ProjectCreateRequest;
import com.paperpilot.api.dto.project.ProjectResponse;
import com.paperpilot.api.mapper.AnalysisTaskMapper;
import com.paperpilot.api.mapper.CodeSymbolMapper;
import com.paperpilot.api.mapper.ConceptCodeMappingMapper;
import com.paperpilot.api.mapper.GitRepositoryMapper;
import com.paperpilot.api.mapper.PaperConceptMapper;
import com.paperpilot.api.mapper.PaperMapper;
import com.paperpilot.api.mapper.ProjectMapper;
import com.paperpilot.api.mapper.StageExecutionMapper;
import com.paperpilot.api.service.ProjectService;
import org.apache.ibatis.session.SqlSession;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.Test;
import org.testcontainers.containers.MySQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import javax.sql.DataSource;
import java.math.BigDecimal;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * 项目服务端到端验证：创建/查询/404 与级联删除（全表 FK 依赖顺序清理）.
 */
@Testcontainers
class ProjectServicePersistenceTest {

    @Container
    static final MySQLContainer<?> MYSQL = new MySQLContainer<>("mysql:8.0");

    @Test
    void createGetAndCascadeDelete() throws Exception {
        DataSource ds = TestSupport.dataSource(MYSQL);
        Flyway.configure().dataSource(ds).load().migrate();

        try (SqlSession session = TestSupport.buildFactory(ds).openSession(true)) {
            ProjectMapper projectMapper = session.getMapper(ProjectMapper.class);
            AnalysisTaskMapper taskMapper = session.getMapper(AnalysisTaskMapper.class);
            StageExecutionMapper stageMapper = session.getMapper(StageExecutionMapper.class);
            PaperMapper paperMapper = session.getMapper(PaperMapper.class);
            PaperConceptMapper paperConceptMapper = session.getMapper(PaperConceptMapper.class);
            GitRepositoryMapper repositoryMapper = session.getMapper(GitRepositoryMapper.class);
            CodeSymbolMapper codeSymbolMapper = session.getMapper(CodeSymbolMapper.class);
            ConceptCodeMappingMapper mappingMapper = session.getMapper(ConceptCodeMappingMapper.class);

            ProjectService projectService = new ProjectService(
                    projectMapper, taskMapper, stageMapper, paperMapper, paperConceptMapper,
                    repositoryMapper, codeSymbolMapper, mappingMapper);

            // 创建
            ProjectResponse created = projectService.create(new ProjectCreateRequest("proj", "desc"));
            Long projectId = created.id();
            assertThat(created.name()).isEqualTo("proj");
            assertThat(projectService.get(projectId).description()).isEqualTo("desc");

            // 造全链路数据
            Paper paper = new Paper();
            paper.setProjectId(projectId);
            paper.setTitle("t");
            paper.setPdfUrl("/tmp/t.pdf");
            paperMapper.insert(paper);

            PaperConcept concept = new PaperConcept();
            concept.setPaperId(paper.getId());
            concept.setConceptKey("pc_0123456789abcdef01234567");
            concept.setConceptName("c");
            concept.setExtractorVersion("test-v1");
            concept.setDecision("MAPPED");
            paperConceptMapper.insert(concept);

            GitRepository repository = new GitRepository();
            repository.setProjectId(projectId);
            repository.setGithubUrl("https://github.com/a/b");
            repositoryMapper.insert(repository);

            CodeSymbol symbol = new CodeSymbol();
            symbol.setRepositoryId(repository.getId());
            symbol.setCommitSha("abc");
            symbol.setFilePath("src/main.py");
            symbol.setSymbolName("main");
            codeSymbolMapper.insert(symbol);

            ConceptCodeMapping mapping = new ConceptCodeMapping();
            mapping.setConceptId(concept.getId());
            mapping.setCodeSymbolId(symbol.getId());
            mapping.setConfidence(new BigDecimal("0.9"));
            mappingMapper.insert(mapping);

            AnalysisTask task = new AnalysisTask();
            task.setProjectId(projectId);
            task.setRequestKey("req-" + projectId);
            task.setStatus(TaskStatus.QUEUED);
            task.setPaperId(paper.getId());
            task.setRepositoryId(repository.getId());
            taskMapper.insert(task);

            StageExecution stage = new StageExecution();
            stage.setTaskId(task.getId());
            stage.setStage(TaskStage.PARSE_PAPER);
            stage.setAttempt(1);
            stage.setStatus(StageExecutionStatus.PENDING);
            stageMapper.insert(stage);

            // 级联删除
            projectService.delete(projectId);

            assertThat(projectMapper.selectById(projectId)).isNull();
            assertThat(taskMapper.selectById(task.getId())).isNull();
            assertThat(stageMapper.selectById(stage.getId())).isNull();
            assertThat(paperMapper.selectById(paper.getId())).isNull();
            assertThat(paperConceptMapper.selectById(concept.getId())).isNull();
            assertThat(repositoryMapper.selectById(repository.getId())).isNull();
            assertThat(codeSymbolMapper.selectById(symbol.getId())).isNull();
            assertThat(mappingMapper.selectById(mapping.getId())).isNull();

            // 删除后查询 → 404
            assertThatThrownBy(() -> projectService.get(projectId))
                    .isInstanceOf(ApiException.class);
        }
    }
}

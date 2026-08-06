package com.paperpilot.api;

import com.paperpilot.api.domain.entity.AnalysisTask;
import com.paperpilot.api.domain.entity.CodeSymbol;
import com.paperpilot.api.domain.entity.ConceptCodeMapping;
import com.paperpilot.api.domain.entity.GitRepository;
import com.paperpilot.api.domain.entity.Paper;
import com.paperpilot.api.domain.entity.PaperConcept;
import com.paperpilot.api.domain.entity.Project;
import com.paperpilot.api.domain.enums.TaskStatus;
import com.paperpilot.api.dto.task.TaskResultResponse;
import com.paperpilot.api.mapper.AnalysisTaskMapper;
import com.paperpilot.api.mapper.CodeSymbolMapper;
import com.paperpilot.api.mapper.ConceptCodeMappingMapper;
import com.paperpilot.api.mapper.FileMapper;
import com.paperpilot.api.mapper.GitRepositoryMapper;
import com.paperpilot.api.mapper.PaperConceptMapper;
import com.paperpilot.api.mapper.PaperMapper;
import com.paperpilot.api.mapper.ProjectMapper;
import com.paperpilot.api.mapper.StageExecutionMapper;
import com.paperpilot.api.progress.TaskProgressProperties;
import com.paperpilot.api.progress.TaskProgressService;
import com.paperpilot.api.service.AnalysisTaskService;
import com.paperpilot.api.service.StageExecutionService;
import com.paperpilot.api.service.TaskEventService;
import com.paperpilot.api.service.TaskResultService;
import org.apache.ibatis.session.SqlSession;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.support.DefaultListableBeanFactory;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.testcontainers.containers.MySQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import javax.sql.DataSource;
import java.math.BigDecimal;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 结果 API 的映射状态：无映射 NO_MAPPINGS、全低置信度 NEEDS_REVIEW、含候选 CANDIDATES.
 */
@Testcontainers
class TaskResultServiceTest {

    @Container
    static final MySQLContainer<?> MYSQL = new MySQLContainer<>("mysql:8.0");

    @Test
    void mappingStatusReflectsNoResultLowConfidenceAndCandidates() throws Exception {
        DataSource ds = TestSupport.dataSource(MYSQL);
        Flyway.configure().dataSource(ds).load().migrate();

        try (SqlSession session = TestSupport.buildFactory(ds).openSession(true)) {
            ProjectMapper projectMapper = session.getMapper(ProjectMapper.class);
            PaperMapper paperMapper = session.getMapper(PaperMapper.class);
            AnalysisTaskMapper taskMapper = session.getMapper(AnalysisTaskMapper.class);
            StageExecutionMapper stageExecutionMapper = session.getMapper(StageExecutionMapper.class);
            FileMapper fileMapper = session.getMapper(FileMapper.class);
            GitRepositoryMapper repositoryMapper = session.getMapper(GitRepositoryMapper.class);
            PaperConceptMapper conceptMapper = session.getMapper(PaperConceptMapper.class);
            ConceptCodeMappingMapper mappingMapper = session.getMapper(ConceptCodeMappingMapper.class);
            CodeSymbolMapper codeSymbolMapper = session.getMapper(CodeSymbolMapper.class);

            StageExecutionService stageService = new StageExecutionService(stageExecutionMapper);
            TaskProgressService noopProgress = new TaskProgressService(
                    new DefaultListableBeanFactory().getBeanProvider(StringRedisTemplate.class),
                    new TaskProgressProperties(), taskMapper);
            AnalysisTaskService taskService = new AnalysisTaskService(
                    taskMapper, projectMapper, fileMapper, paperMapper, repositoryMapper,
                    stageService, new TaskEventService(), noopProgress, event -> {
                    });
            TaskResultService resultService = new TaskResultService(
                    taskService, stageService, paperMapper, repositoryMapper,
                    conceptMapper, mappingMapper, codeSymbolMapper);

            // 三个独立任务：无映射 / 低置信度 / 含候选
            long[] noMapping = insertChain(projectMapper, repositoryMapper, paperMapper,
                    codeSymbolMapper, taskMapper, "c0");
            long[] needsReview = insertChain(projectMapper, repositoryMapper, paperMapper,
                    codeSymbolMapper, taskMapper, "c1");
            long[] candidates = insertChain(projectMapper, repositoryMapper, paperMapper,
                    codeSymbolMapper, taskMapper, "c2");

            insertConceptAndMapping(conceptMapper, mappingMapper, needsReview[0], needsReview[1], "0.2");
            insertConceptAndMapping(conceptMapper, mappingMapper, candidates[0], candidates[1], "0.8");

            assertThat(resultService.getResult(noMapping[2]).mappingStatus()).isEqualTo("NO_MAPPINGS");
            assertThat(resultService.getResult(noMapping[2]).mappings()).isEmpty();

            TaskResultResponse needsReviewResp = resultService.getResult(needsReview[2]);
            assertThat(needsReviewResp.mappingStatus()).isEqualTo("NEEDS_REVIEW");
            assertThat(needsReviewResp.mappings()).hasSize(1);
            assertThat(needsReviewResp.mappings().get(0).candidates().get(0).status())
                    .isEqualTo("NEEDS_REVIEW");

            TaskResultResponse candidatesResp = resultService.getResult(candidates[2]);
            assertThat(candidatesResp.mappingStatus()).isEqualTo("CANDIDATES");
            assertThat(candidatesResp.mappings().get(0).candidates().get(0).status())
                    .isEqualTo("CANDIDATE");
        }
    }

    /** 返回 {paperId, symbolId, taskId}。 */
    private long[] insertChain(ProjectMapper projectMapper, GitRepositoryMapper repositoryMapper,
                               PaperMapper paperMapper, CodeSymbolMapper codeSymbolMapper,
                               AnalysisTaskMapper taskMapper, String title) {
        Project project = new Project();
        project.setName("p-" + UUID.randomUUID());
        projectMapper.insert(project);

        GitRepository repo = new GitRepository();
        repo.setProjectId(project.getId());
        repo.setGithubUrl("https://github.com/paperpilot/patchtst");
        repositoryMapper.insert(repo);

        Paper paper = new Paper();
        paper.setProjectId(project.getId());
        paper.setTitle(title);
        paper.setPdfUrl("http://example.com/paper.pdf");
        paperMapper.insert(paper);

        CodeSymbol symbol = new CodeSymbol();
        symbol.setRepositoryId(repo.getId());
        symbol.setCommitSha("f".repeat(40));
        symbol.setFilePath("model.py");
        symbol.setSymbolName("PatchTST");
        symbol.setSymbolType("class");
        symbol.setLineNumber(5);
        codeSymbolMapper.insert(symbol);

        AnalysisTask task = new AnalysisTask();
        task.setProjectId(project.getId());
        task.setPaperId(paper.getId());
        task.setRepositoryId(repo.getId());
        task.setStatus(TaskStatus.SUCCEEDED);
        task.setRequestKey("req-" + UUID.randomUUID());
        taskMapper.insert(task);
        return new long[]{paper.getId(), symbol.getId(), task.getId()};
    }

    private void insertConceptAndMapping(PaperConceptMapper conceptMapper,
                                         ConceptCodeMappingMapper mappingMapper,
                                         long paperId, long symbolId, String confidence) {
        PaperConcept concept = new PaperConcept();
        concept.setPaperId(paperId);
        concept.setConceptName("channel independence");
        concept.setEvidenceText("evidence");
        conceptMapper.upsert(concept);
        PaperConcept saved = conceptMapper.selectList(null).stream()
                .filter(c -> c.getPaperId().equals(paperId)).findFirst().orElseThrow();
        ConceptCodeMapping mapping = new ConceptCodeMapping();
        mapping.setConceptId(saved.getId());
        mapping.setCodeSymbolId(symbolId);
        mapping.setConfidence(new BigDecimal(confidence));
        mappingMapper.upsert(mapping);
    }
}

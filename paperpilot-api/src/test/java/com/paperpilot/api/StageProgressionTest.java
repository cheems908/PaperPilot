package com.paperpilot.api;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.conditions.update.LambdaUpdateWrapper;
import com.paperpilot.api.domain.entity.AnalysisTask;
import com.paperpilot.api.domain.entity.Project;
import com.paperpilot.api.domain.entity.GitRepository;
import com.paperpilot.api.domain.entity.StageExecution;
import com.paperpilot.api.domain.enums.StageExecutionStatus;
import com.paperpilot.api.domain.enums.TaskStage;
import com.paperpilot.api.domain.enums.TaskStatus;
import com.paperpilot.api.dto.mq.StageTaskMessage;
import com.paperpilot.api.mapper.AnalysisTaskMapper;
import com.paperpilot.api.mapper.ProjectMapper;
import com.paperpilot.api.mapper.GitRepositoryMapper;
import com.paperpilot.api.mapper.StageExecutionMapper;
import com.paperpilot.api.mq.StageMessageProducer;
import com.paperpilot.api.service.StageProgressionService;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Primary;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.transaction.PlatformTransactionManager;
import org.testcontainers.containers.MySQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import javax.sql.DataSource;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;

/**
 * 阶段推进集成测试（真实 DB + 事务）：推进下一阶段并派发一次、重复推进不重复派发、
 * 最后阶段任务 SUCCEEDED + finishedAt、CANCELLED 任务不派发.
 */
@SpringBootTest
@Testcontainers
class StageProgressionTest {

    @Container
    static final MySQLContainer<?> MYSQL = new MySQLContainer<>("mysql:8.0");

    @MockBean
    StageMessageProducer producer;

    @Autowired
    StageProgressionService progressionService;
    @Autowired
    AnalysisTaskMapper taskMapper;
    @Autowired
    StageExecutionMapper stageExecutionMapper;
    @Autowired
    ProjectMapper projectMapper;
    @Autowired
    GitRepositoryMapper repositoryMapper;

    @BeforeAll
    static void migrate() {
        Flyway.configure().dataSource(TestSupport.dataSource(MYSQL)).load().migrate();
    }

    @Test
    void advancesToNextStageAndDispatchesOnce() {
        long taskId = insertRunningTaskWithStages();
        markSucceeded(taskId, TaskStage.PARSE_PAPER);
        StageExecution parse = stageBy(taskId, TaskStage.PARSE_PAPER);

        progressionService.advance(StageTaskMessage.create(taskId, parse.getId(), TaskStage.PARSE_PAPER, 1));

        StageExecution clone = stageBy(taskId, TaskStage.CLONE_REPOSITORY);
        assertThat(clone.getInputSnapshot())
                .contains("\"stage\":\"CLONE_REPOSITORY\"")
                .contains("\"upstreamStageExecutionId\":" + parse.getId())
                .contains("\"githubUrl\":\"https://github.com/paperpilot/patchtst\"");
        ArgumentCaptor<StageTaskMessage> captor = ArgumentCaptor.forClass(StageTaskMessage.class);
        verify(producer, times(1)).send(captor.capture());
        StageTaskMessage next = captor.getValue();
        assertThat(next.taskId()).isEqualTo(taskId);
        assertThat(next.stage()).isEqualTo(TaskStage.CLONE_REPOSITORY);
        assertThat(next.stageExecutionId()).isEqualTo(clone.getId());
        assertThat(next.attempt()).isEqualTo(1);
    }

    @Test
    void doubleAdvanceDoesNotDispatchTwice() {
        long taskId = insertRunningTaskWithStages();
        markSucceeded(taskId, TaskStage.PARSE_PAPER);
        StageExecution parse = stageBy(taskId, TaskStage.PARSE_PAPER);
        StageTaskMessage message = StageTaskMessage.create(taskId, parse.getId(), TaskStage.PARSE_PAPER, 1);

        progressionService.advance(message);
        progressionService.advance(message); // 同一成功回调两次

        verify(producer, times(1)).send(any());
        StageExecution clone = stageBy(taskId, TaskStage.CLONE_REPOSITORY);
        assertThat(clone.getInputSnapshot()).isNotNull();
    }

    @Test
    void cloneOutputBecomesIndexSource() {
        long taskId = insertRunningTaskWithStages();
        String output = "{\"schemaVersion\":1,\"workerVersion\":\"test\",\"artifactRefs\":[],"
                + "\"summary\":{\"commitSha\":\"" + "a".repeat(40)
                + "\",\"workspaceRef\":\"task-1/stage-2\"}}";
        markSucceededWithOutput(taskId, TaskStage.CLONE_REPOSITORY, output);
        StageExecution clone = stageBy(taskId, TaskStage.CLONE_REPOSITORY);

        progressionService.advance(StageTaskMessage.create(
                taskId, clone.getId(), TaskStage.CLONE_REPOSITORY, 1));

        assertThat(stageBy(taskId, TaskStage.INDEX_CODE).getInputSnapshot())
                .contains("\"workspaceRef\":\"task-1/stage-2\"")
                .contains("\"commitSha\":\"" + "a".repeat(40) + "\"");
    }

    @Test
    void paperAndIndexEvidenceBecomeMappingInput() {
        long taskId = insertRunningTaskWithStages();
        markSucceededWithOutput(taskId, TaskStage.PARSE_PAPER,
                "{\"schemaVersion\":1,\"workerVersion\":\"test\",\"artifactRefs\":[],"
                        + "\"summary\":{\"paper\":{\"title\":\"PatchTST\",\"sections\":[]}}}");
        markSucceededWithOutput(taskId, TaskStage.INDEX_CODE,
                "{\"schemaVersion\":1,\"workerVersion\":\"test\",\"artifactRefs\":[],"
                        + "\"summary\":{\"commitSha\":\"" + "b".repeat(40) + "\","
                        + "\"symbols\":[{\"filePath\":\"model.py\",\"qualifiedName\":\"Model.forward\"}]}}}");
        StageExecution index = stageBy(taskId, TaskStage.INDEX_CODE);

        progressionService.advance(StageTaskMessage.create(taskId, index.getId(), TaskStage.INDEX_CODE, 1));

        assertThat(stageBy(taskId, TaskStage.MAP_CONCEPTS).getInputSnapshot())
                .contains("\"paper\":{\"title\":\"PatchTST\"")
                .contains("\"symbols\":[{\"filePath\":\"model.py\"")
                .contains("\"commitSha\":\"" + "b".repeat(40) + "\"");
    }

    @Test
    void lastStageCompletesTaskAsSucceeded() {
        long taskId = insertRunningTaskWithStages();
        markSucceeded(taskId, TaskStage.MAP_CONCEPTS);
        StageExecution map = stageBy(taskId, TaskStage.MAP_CONCEPTS);

        progressionService.advance(StageTaskMessage.create(taskId, map.getId(), TaskStage.MAP_CONCEPTS, 1));

        AnalysisTask task = taskMapper.selectById(taskId);
        assertThat(task.getStatus()).isEqualTo(TaskStatus.SUCCEEDED);
        assertThat(task.getFinishedAt()).isNotNull();
        verify(producer, never()).send(any());
    }

    @Test
    void cancelledTaskDoesNotDispatchNext() {
        long taskId = insertRunningTaskWithStages();
        markSucceeded(taskId, TaskStage.PARSE_PAPER);
        AnalysisTask task = taskMapper.selectById(taskId);
        task.setStatus(TaskStatus.CANCELLED);
        taskMapper.updateById(task);
        StageExecution parse = stageBy(taskId, TaskStage.PARSE_PAPER);

        progressionService.advance(StageTaskMessage.create(taskId, parse.getId(), TaskStage.PARSE_PAPER, 1));

        verify(producer, never()).send(any());
        assertThat(stageBy(taskId, TaskStage.CLONE_REPOSITORY).getInputSnapshot()).isNull();
    }

    // ── helpers ──────────────────────────────────────────────────────────

    private long insertRunningTaskWithStages() {
        Project project = new Project();
        project.setName("p");
        projectMapper.insert(project);

        GitRepository repository = new GitRepository();
        repository.setProjectId(project.getId());
        repository.setGithubUrl("https://github.com/paperpilot/patchtst");
        repository.setBranch("main");
        repositoryMapper.insert(repository);

        AnalysisTask task = new AnalysisTask();
        task.setProjectId(project.getId());
        task.setRepositoryId(repository.getId());
        task.setStatus(TaskStatus.RUNNING);
        task.setRequestKey("req-" + UUID.randomUUID());
        taskMapper.insert(task);

        for (TaskStage stage : TaskStage.MVP_STAGES) {
            StageExecution s = new StageExecution();
            s.setTaskId(task.getId());
            s.setStage(stage);
            s.setAttempt(1);
            s.setStatus(StageExecutionStatus.PENDING);
            stageExecutionMapper.insert(s);
        }
        return task.getId();
    }

    private void markSucceeded(long taskId, TaskStage stage) {
        stageExecutionMapper.update(null, new LambdaUpdateWrapper<StageExecution>()
                .eq(StageExecution::getTaskId, taskId)
                .eq(StageExecution::getStage, stage)
                .eq(StageExecution::getAttempt, 1)
                .set(StageExecution::getStatus, StageExecutionStatus.SUCCEEDED));
    }

    private void markSucceededWithOutput(long taskId, TaskStage stage, String output) {
        stageExecutionMapper.update(null, new LambdaUpdateWrapper<StageExecution>()
                .eq(StageExecution::getTaskId, taskId)
                .eq(StageExecution::getStage, stage)
                .eq(StageExecution::getAttempt, 1)
                .set(StageExecution::getStatus, StageExecutionStatus.SUCCEEDED)
                .set(StageExecution::getOutputSnapshot, output));
    }

    private StageExecution stageBy(long taskId, TaskStage stage) {
        return stageExecutionMapper.selectOne(new LambdaQueryWrapper<StageExecution>()
                .eq(StageExecution::getTaskId, taskId)
                .eq(StageExecution::getStage, stage)
                .eq(StageExecution::getAttempt, 1));
    }

    @TestConfiguration
    static class DbConfig {

        @Bean
        @Primary
        DataSource dataSource() {
            return TestSupport.dataSource(MYSQL);
        }

        @Bean
        PlatformTransactionManager transactionManager(DataSource dataSource) {
            return new DataSourceTransactionManager(dataSource);
        }
    }
}

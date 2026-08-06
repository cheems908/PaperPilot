package com.paperpilot.api;

import com.paperpilot.api.domain.entity.AnalysisTask;
import com.paperpilot.api.domain.entity.Project;
import com.paperpilot.api.domain.entity.StageExecution;
import com.paperpilot.api.domain.enums.StageExecutionStatus;
import com.paperpilot.api.domain.enums.TaskStage;
import com.paperpilot.api.domain.enums.TaskStatus;
import com.paperpilot.api.dto.mq.StageTaskMessage;
import com.paperpilot.api.dto.worker.WorkerStageResponse;
import com.paperpilot.api.mapper.AnalysisTaskMapper;
import com.paperpilot.api.mapper.ProjectMapper;
import com.paperpilot.api.mapper.StageExecutionMapper;
import com.paperpilot.api.service.AnalysisTaskService;
import com.paperpilot.api.service.StageExecutionResult;
import com.paperpilot.api.service.StageOrchestrator;
import com.paperpilot.api.worker.WorkerClient;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
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
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.when;

/** Worker 完成与取消并发时，取消胜出后不得提交结果或推进任务。 */
@SpringBootTest
@Testcontainers
class CancellationRaceTest {

    @Container
    static final MySQLContainer<?> MYSQL = new MySQLContainer<>("mysql:8.0");

    @MockBean WorkerClient workerClient;
    @Autowired StageOrchestrator orchestrator;
    @Autowired AnalysisTaskService taskService;
    @Autowired ProjectMapper projectMapper;
    @Autowired AnalysisTaskMapper taskMapper;
    @Autowired StageExecutionMapper stageMapper;

    @BeforeAll
    static void migrate() {
        Flyway.configure().dataSource(TestSupport.dataSource(MYSQL)).load().migrate();
    }

    @Test
    void cancellationWinsWhileWorkerIsExecuting() throws Exception {
        long[] ids = insertQueuedStage();
        CountDownLatch workerEntered = new CountDownLatch(1);
        CountDownLatch releaseWorker = new CountDownLatch(1);
        when(workerClient.execute(any())).thenAnswer(invocation -> {
            workerEntered.countDown();
            assertThat(releaseWorker.await(10, TimeUnit.SECONDS)).isTrue();
            return new WorkerStageResponse(1, true, "ok", List.of(), null, "0.1");
        });

        ExecutorService pool = Executors.newSingleThreadExecutor();
        Future<StageExecutionResult> future = pool.submit(() -> orchestrator.orchestrate(
                StageTaskMessage.create(ids[0], ids[1], TaskStage.PARSE_PAPER, 1)));
        assertThat(workerEntered.await(10, TimeUnit.SECONDS)).isTrue();

        taskService.cancel(ids[0]);
        releaseWorker.countDown();
        StageExecutionResult result = future.get(10, TimeUnit.SECONDS);
        pool.shutdownNow();

        assertThat(result.skipped()).isTrue();
        assertThat(taskMapper.selectById(ids[0]).getStatus()).isEqualTo(TaskStatus.CANCELLED);
        StageExecution stage = stageMapper.selectById(ids[1]);
        assertThat(stage.getStatus()).isEqualTo(StageExecutionStatus.CANCELLED);
        assertThat(stage.getOutputSnapshot()).isNull();
    }

    private long[] insertQueuedStage() {
        Project project = new Project();
        project.setName("cancel-race-" + UUID.randomUUID());
        projectMapper.insert(project);
        AnalysisTask task = new AnalysisTask();
        task.setProjectId(project.getId());
        task.setRequestKey("cancel-race-" + UUID.randomUUID());
        task.setStatus(TaskStatus.QUEUED);
        taskMapper.insert(task);
        StageExecution stage = new StageExecution();
        stage.setTaskId(task.getId());
        stage.setStage(TaskStage.PARSE_PAPER);
        stage.setAttempt(1);
        stage.setStatus(StageExecutionStatus.PENDING);
        stageMapper.insert(stage);
        return new long[]{task.getId(), stage.getId()};
    }

    @TestConfiguration
    static class DbConfig {
        @Bean @Primary
        DataSource dataSource() {
            return TestSupport.dataSource(MYSQL);
        }

        @Bean
        PlatformTransactionManager transactionManager(DataSource dataSource) {
            return new DataSourceTransactionManager(dataSource);
        }
    }
}

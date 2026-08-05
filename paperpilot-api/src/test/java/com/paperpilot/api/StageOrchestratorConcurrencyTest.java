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
import com.paperpilot.api.service.StageExecutionResult;
import com.paperpilot.api.service.StageOrchestrator;
import com.paperpilot.api.worker.WorkerClient;
import com.paperpilot.api.worker.WorkerErrorCode;
import com.paperpilot.api.worker.WorkerException;
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
import java.util.Map;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.stream.Collectors;
import java.util.stream.IntStream;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 幂等阶段编排器集成测试（真实 DB + 事务）：同一消息并发仅一次 Worker 调用、
 * Worker 成功结果与 SUCCEEDED 原子落库、保存失败不标 SUCCEEDED、失败错误快照可查询.
 */
@SpringBootTest
@Testcontainers
class StageOrchestratorConcurrencyTest {

    @Container
    static final MySQLContainer<?> MYSQL = new MySQLContainer<>("mysql:8.0");

    @MockBean
    WorkerClient workerClient;

    @Autowired
    StageOrchestrator orchestrator;
    @Autowired
    AnalysisTaskMapper taskMapper;
    @Autowired
    StageExecutionMapper stageExecutionMapper;
    @Autowired
    ProjectMapper projectMapper;

    @BeforeAll
    static void migrate() {
        Flyway.configure().dataSource(TestSupport.dataSource(MYSQL)).load().migrate();
    }

    @Test
    void concurrentSameMessageOnlyOneWorkerCall() throws Exception {
        when(workerClient.execute(any())).thenReturn(successResponse("0.1"));
        long[] ids = insertTaskAndStage();
        StageTaskMessage message = StageTaskMessage.create(ids[0], ids[1], TaskStage.PARSE_PAPER, 1);

        int threads = 8;
        ExecutorService pool = Executors.newFixedThreadPool(threads);
        CountDownLatch start = new CountDownLatch(1);
        ConcurrentLinkedQueue<StageExecutionResult> results = new ConcurrentLinkedQueue<>();
        List<Future<?>> futures = IntStream.range(0, threads).mapToObj(i -> pool.submit(() -> {
            try {
                start.await();
                results.add(orchestrator.orchestrate(message));
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
        })).collect(Collectors.toList());

        start.countDown();
        pool.shutdown();
        assertThat(pool.awaitTermination(30, TimeUnit.SECONDS)).isTrue();

        // 并发下仅一次 Worker 调用
        verify(workerClient, times(1)).execute(any());
        // 恰好一个线程获得执行权并成功
        assertThat(results.stream().filter(StageExecutionResult::success).count()).isEqualTo(1);
        StageExecution stage = stageExecutionMapper.selectById(ids[1]);
        assertThat(stage.getStatus()).isEqualTo(StageExecutionStatus.SUCCEEDED);
        assertThat(stage.getOutputSnapshot()).contains("\"schemaVersion\":1");
    }

    @Test
    void workerSuccessPersistsResultAtomically() {
        when(workerClient.execute(any())).thenReturn(successResponse("0.1"));
        long[] ids = insertTaskAndStage();

        StageExecutionResult r = orchestrator.orchestrate(
                StageTaskMessage.create(ids[0], ids[1], TaskStage.PARSE_PAPER, 1));

        assertThat(r.success()).isTrue();
        StageExecution stage = stageExecutionMapper.selectById(ids[1]);
        assertThat(stage.getStatus()).isEqualTo(StageExecutionStatus.SUCCEEDED);
        assertThat(stage.getOutputSnapshot())
                .contains("\"workerVersion\":\"0.1\"")
                .contains("\"title\":\"t\"")
                .contains("\"metrics\":{\"pages\":5}");
        assertThat(stage.getFinishedAt()).isNotNull();
        assertThat(stage.getErrorSnapshot()).isNull();
    }

    @Test
    void saveFailureDoesNotMarkSucceeded() {
        // workerVersion=null → 输出快照构建失败 → 保存事务回滚 → 阶段不标 SUCCEEDED
        when(workerClient.execute(any())).thenReturn(new WorkerStageResponse(
                WorkerStageResponse.SCHEMA_VERSION, true, Map.of("title", "t"), List.of(), Map.of(), null));
        long[] ids = insertTaskAndStage();

        StageExecutionResult r = orchestrator.orchestrate(
                StageTaskMessage.create(ids[0], ids[1], TaskStage.PARSE_PAPER, 1));

        assertThat(r.success()).isFalse();
        assertThat(r.detail()).contains("RESULT_SAVE_FAILED");
        StageExecution stage = stageExecutionMapper.selectById(ids[1]);
        assertThat(stage.getStatus()).isNotEqualTo(StageExecutionStatus.SUCCEEDED);
        // 失败结果被记录（错误快照可查询）
        assertThat(stage.getErrorSnapshot()).contains("RESULT_SAVE_FAILED");
    }

    @Test
    void workerFailureDoesNotMarkSuccessAndErrorQueryable() {
        when(workerClient.execute(any())).thenThrow(new WorkerException(WorkerErrorCode.HTTP_5XX, 500, "boom"));
        long[] ids = insertTaskAndStage();

        StageExecutionResult r = orchestrator.orchestrate(
                StageTaskMessage.create(ids[0], ids[1], TaskStage.PARSE_PAPER, 1));

        assertThat(r.success()).isFalse();
        StageExecution stage = stageExecutionMapper.selectById(ids[1]);
        assertThat(stage.getStatus()).isEqualTo(StageExecutionStatus.FAILED);
        assertThat(stage.getErrorSnapshot())
                .contains("\"errorCode\":\"HTTP_5XX\"")
                .contains("\"retryable\":true");
        assertThat(stage.getOutputSnapshot()).isNull();
        // 任务 RUNNING→FAILED（经状态机）
        assertThat(taskMapper.selectById(ids[0]).getStatus()).isEqualTo(TaskStatus.FAILED);
    }

    // ── helpers ──────────────────────────────────────────────────────────

    private long[] insertTaskAndStage() {
        Project project = new Project();
        project.setName("p");
        projectMapper.insert(project);

        AnalysisTask task = new AnalysisTask();
        task.setProjectId(project.getId());
        task.setStatus(TaskStatus.QUEUED);
        task.setRequestKey("req-" + java.util.UUID.randomUUID());
        taskMapper.insert(task);

        StageExecution stage = new StageExecution();
        stage.setTaskId(task.getId());
        stage.setStage(TaskStage.PARSE_PAPER);
        stage.setAttempt(1);
        stage.setStatus(StageExecutionStatus.PENDING);
        stageExecutionMapper.insert(stage);
        return new long[]{task.getId(), stage.getId()};
    }

    private WorkerStageResponse successResponse(String workerVersion) {
        return new WorkerStageResponse(WorkerStageResponse.SCHEMA_VERSION, true,
                Map.of("title", "t"), List.of(), Map.of("pages", 5), workerVersion);
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

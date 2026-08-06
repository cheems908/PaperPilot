package com.paperpilot.api.retry;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.paperpilot.api.TestSupport;
import com.paperpilot.api.domain.entity.AnalysisTask;
import com.paperpilot.api.domain.entity.Project;
import com.paperpilot.api.domain.entity.StageExecution;
import com.paperpilot.api.domain.enums.StageExecutionStatus;
import com.paperpilot.api.domain.enums.TaskStage;
import com.paperpilot.api.domain.enums.TaskStatus;
import com.paperpilot.api.dto.mq.StageTaskMessage;
import com.paperpilot.api.mapper.AnalysisTaskMapper;
import com.paperpilot.api.mapper.ProjectMapper;
import com.paperpilot.api.mapper.StageExecutionMapper;
import com.paperpilot.api.mq.StageMessageProducer;
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
import java.time.LocalDateTime;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.clearInvocations;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;

/** 真实 MySQL 条件更新验证：两个扫描器并发时仅一个创建并派发下一 attempt。 */
@SpringBootTest(properties = "paperpilot.retry.scan-interval=PT1H")
@Testcontainers
class RetrySchedulerConcurrencyTest {

    @Container
    static final MySQLContainer<?> MYSQL = new MySQLContainer<>("mysql:8.0");

    @MockBean
    StageMessageProducer producer;

    @Autowired RetryScheduler scheduler;
    @Autowired ProjectMapper projectMapper;
    @Autowired AnalysisTaskMapper taskMapper;
    @Autowired StageExecutionMapper stageMapper;

    @BeforeAll
    static void migrate() {
        Flyway.configure().dataSource(TestSupport.dataSource(MYSQL)).load().migrate();
    }

    @Test
    void concurrentScannersCreateAndDispatchOnlyOneNextAttempt() throws Exception {
        long[] ids = insertWaitingRetry();
        clearInvocations(producer);

        CountDownLatch start = new CountDownLatch(1);
        ExecutorService pool = Executors.newFixedThreadPool(2);
        for (int i = 0; i < 2; i++) {
            pool.submit(() -> {
                try {
                    start.await();
                    scheduler.scanDueRetries();
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                }
            });
        }
        start.countDown();
        pool.shutdown();
        assertThat(pool.awaitTermination(20, TimeUnit.SECONDS)).isTrue();

        List<StageExecution> history = stageMapper.selectList(new LambdaQueryWrapper<StageExecution>()
                .eq(StageExecution::getTaskId, ids[0])
                .eq(StageExecution::getStage, TaskStage.PARSE_PAPER)
                .orderByAsc(StageExecution::getAttempt));
        assertThat(history).hasSize(2);
        assertThat(history.get(0).getId()).isEqualTo(ids[1]);
        assertThat(history.get(0).getStatus()).isEqualTo(StageExecutionStatus.FAILED);
        assertThat(history.get(0).getErrorSnapshot()).contains("WORKER_TIMEOUT");
        assertThat(history.get(1).getAttempt()).isEqualTo(2);
        assertThat(history.get(1).getStatus()).isEqualTo(StageExecutionStatus.PENDING);
        assertThat(taskMapper.selectById(ids[0]).getStatus()).isEqualTo(TaskStatus.QUEUED);

        verify(producer, times(1)).send(any(StageTaskMessage.class));
    }

    private long[] insertWaitingRetry() {
        Project project = new Project();
        project.setName("retry-concurrency");
        projectMapper.insert(project);

        AnalysisTask task = new AnalysisTask();
        task.setProjectId(project.getId());
        task.setRequestKey("retry-" + UUID.randomUUID());
        task.setStatus(TaskStatus.WAITING_RETRY);
        taskMapper.insert(task);

        StageExecution stage = new StageExecution();
        stage.setTaskId(task.getId());
        stage.setStage(TaskStage.PARSE_PAPER);
        stage.setAttempt(1);
        stage.setStatus(StageExecutionStatus.WAITING_RETRY);
        stage.setInputSnapshot("{\"schemaVersion\":1}");
        stage.setErrorSnapshot("{\"schemaVersion\":1,\"errorCode\":\"WORKER_TIMEOUT\",\"retryable\":true}");
        stage.setErrorMessage("timeout");
        stage.setNextRetryAt(LocalDateTime.now().minusSeconds(1));
        stage.setFinishedAt(LocalDateTime.now().minusSeconds(2));
        stageMapper.insert(stage);
        return new long[]{task.getId(), stage.getId()};
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

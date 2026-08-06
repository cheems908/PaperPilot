package com.paperpilot.api.recovery;

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
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Primary;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.transaction.PlatformTransactionManager;
import org.testcontainers.containers.MySQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import javax.sql.DataSource;
import java.time.LocalDateTime;
import java.time.Duration;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.clearInvocations;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;

@SpringBootTest(properties = {
        "paperpilot.recovery.queued-timeout=PT1S",
        "paperpilot.recovery.running-timeout=PT2S",
        "paperpilot.recovery.heartbeat-interval=PT1S"
})
@Testcontainers
class TaskRecoverySchedulerTest {

    @Container
    static final MySQLContainer<?> MYSQL = new MySQLContainer<>("mysql:8.0");

    @MockBean StageMessageProducer producer;
    @Autowired TaskRecoveryScheduler scheduler;
    @Autowired ProjectMapper projectMapper;
    @Autowired AnalysisTaskMapper taskMapper;
    @Autowired StageExecutionMapper stageMapper;
    @Autowired JdbcTemplate jdbcTemplate;
    @Autowired RecoveryProperties properties;

    @BeforeAll
    static void migrate() {
        Flyway.configure().dataSource(TestSupport.dataSource(MYSQL)).load().migrate();
    }

    @BeforeEach
    void resetMock() {
        clearInvocations(producer);
    }

    @Test
    void twoInstancesRecoverLostQueuedMessageOnlyOnce() throws Exception {
        long[] ids = insert(TaskStatus.QUEUED, StageExecutionStatus.PENDING);
        jdbcTemplate.update("UPDATE stage_execution SET input_snapshot=?, updated_at=? WHERE id=?",
                "{\"schemaVersion\":1}", LocalDateTime.now().minusMinutes(1), ids[1]);

        CountDownLatch start = new CountDownLatch(1);
        ExecutorService pool = Executors.newFixedThreadPool(2);
        for (int i = 0; i < 2; i++) {
            pool.submit(() -> {
                try {
                    start.await();
                    scheduler.recover();
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                }
            });
        }
        start.countDown();
        pool.shutdown();
        assertThat(pool.awaitTermination(20, TimeUnit.SECONDS)).isTrue();

        verify(producer, times(1)).send(any(StageTaskMessage.class));
        assertThat(stageMapper.selectById(ids[1]).getStatus()).isEqualTo(StageExecutionStatus.PENDING);
    }

    @Test
    void pendingFutureStageWithoutInputIsNeverRecovered() {
        long[] ids = insert(TaskStatus.QUEUED, StageExecutionStatus.PENDING);
        jdbcTemplate.update("UPDATE stage_execution SET updated_at=? WHERE id=?",
                LocalDateTime.now().minusMinutes(1), ids[1]);

        scheduler.recover();

        verify(producer, never()).send(any());
        assertThat(stageMapper.selectById(ids[1]).getStatus()).isEqualTo(StageExecutionStatus.PENDING);
    }

    @Test
    void expiredRunningLeaseBecomesStructuredWaitingRetry() {
        long[] ids = insert(TaskStatus.RUNNING, StageExecutionStatus.RUNNING);
        jdbcTemplate.update("UPDATE stage_execution SET started_at=?, heartbeat_at=? WHERE id=?",
                LocalDateTime.now().minusMinutes(11), LocalDateTime.now().minusMinutes(10), ids[1]);

        scheduler.recover();

        StageExecution recovered = stageMapper.selectById(ids[1]);
        assertThat(recovered.getStatus()).isEqualTo(StageExecutionStatus.WAITING_RETRY);
        assertThat(recovered.getErrorSnapshot()).contains("EXECUTION_LEASE_EXPIRED");
        assertThat(recovered.getNextRetryAt()).isNotNull();
        assertThat(taskMapper.selectById(ids[0]).getStatus()).isEqualTo(TaskStatus.WAITING_RETRY);
    }

    @Test
    void freshHeartbeatIsNotRecovered() {
        assertThat(properties.runningTimeout()).isEqualTo(Duration.ofSeconds(2));
        long[] ids = insert(TaskStatus.RUNNING, StageExecutionStatus.RUNNING);
        jdbcTemplate.update("UPDATE stage_execution SET started_at=?, heartbeat_at=? WHERE id=?",
                LocalDateTime.now().minusMinutes(2), LocalDateTime.now(), ids[1]);

        scheduler.recover();

        assertThat(stageMapper.selectById(ids[1]).getStatus()).isEqualTo(StageExecutionStatus.RUNNING);
        assertThat(taskMapper.selectById(ids[0]).getStatus()).isEqualTo(TaskStatus.RUNNING);
    }

    @Test
    void cancelledTaskIsNeverRecovered() {
        long[] ids = insert(TaskStatus.CANCELLED, StageExecutionStatus.PENDING);
        jdbcTemplate.update("UPDATE stage_execution SET updated_at=? WHERE id=?",
                LocalDateTime.now().minusMinutes(1), ids[1]);

        scheduler.recover();

        verify(producer, never()).send(any());
        assertThat(taskMapper.selectById(ids[0]).getStatus()).isEqualTo(TaskStatus.CANCELLED);
    }

    private long[] insert(TaskStatus taskStatus, StageExecutionStatus stageStatus) {
        Project project = new Project();
        project.setName("recovery-" + UUID.randomUUID());
        projectMapper.insert(project);
        AnalysisTask task = new AnalysisTask();
        task.setProjectId(project.getId());
        task.setRequestKey("recovery-" + UUID.randomUUID());
        task.setStatus(taskStatus);
        taskMapper.insert(task);
        StageExecution stage = new StageExecution();
        stage.setTaskId(task.getId());
        stage.setStage(TaskStage.PARSE_PAPER);
        stage.setAttempt(1);
        stage.setStatus(stageStatus);
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

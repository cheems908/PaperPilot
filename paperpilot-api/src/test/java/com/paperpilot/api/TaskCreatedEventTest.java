package com.paperpilot.api;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.paperpilot.api.domain.entity.AnalysisTask;
import com.paperpilot.api.domain.entity.File;
import com.paperpilot.api.domain.entity.Project;
import com.paperpilot.api.domain.entity.StageExecution;
import com.paperpilot.api.domain.enums.TaskStage;
import com.paperpilot.api.dto.mq.StageTaskMessage;
import com.paperpilot.api.dto.task.CreateTaskRequest;
import com.paperpilot.api.dto.task.TaskResponse;
import com.paperpilot.api.mapper.AnalysisTaskMapper;
import com.paperpilot.api.mapper.FileMapper;
import com.paperpilot.api.mapper.ProjectMapper;
import com.paperpilot.api.mapper.StageExecutionMapper;
import com.paperpilot.api.mq.StageMessageProducer;
import com.paperpilot.api.mq.TaskCreatedEvent;
import com.paperpilot.api.service.AnalysisTaskService;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Primary;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import org.testcontainers.containers.MySQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import javax.sql.DataSource;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;

/**
 * 创建任务事务后派发首阶段：提交后恰好派发一次、幂等 request_key 只派发一次、
 * 事务回滚不派发（无幽灵消息）.
 *
 * <p>真实 Spring 上下文 + Testcontainers DB；{@link StageMessageProducer} 以 mock 替换，
 * 只验证派发次数与消息内容，不连真实 RocketMQ。
 */
@SpringBootTest
@Testcontainers
class TaskCreatedEventTest {

    @Container
    static final MySQLContainer<?> MYSQL = new MySQLContainer<>("mysql:8.0");

    @MockBean
    StageMessageProducer producer;

    @Autowired
    AnalysisTaskService taskService;
    @Autowired
    ProjectMapper projectMapper;
    @Autowired
    FileMapper fileMapper;
    @Autowired
    AnalysisTaskMapper taskMapper;
    @Autowired
    StageExecutionMapper stageExecutionMapper;
    @Autowired
    ApplicationEventPublisher eventPublisher;
    @Autowired
    PlatformTransactionManager transactionManager;

    @BeforeAll
    static void migrate() {
        Flyway.configure().dataSource(TestSupport.dataSource(MYSQL)).load().migrate();
    }

    @Test
    void createTaskDispatchesFirstStageAfterCommit() {
        Project project = insertProject();
        File file = insertFile();

        TaskResponse resp = taskService.createTask(project.getId(),
                new CreateTaskRequest(file.getId(), null, null, "req-commit"));

        assertThat(resp.status()).isEqualTo("QUEUED");
        ArgumentCaptor<StageTaskMessage> captor = ArgumentCaptor.forClass(StageTaskMessage.class);
        verify(producer, times(1)).send(captor.capture());
        StageTaskMessage msg = captor.getValue();
        assertThat(msg.schemaVersion()).isEqualTo(StageTaskMessage.SCHEMA_VERSION);
        assertThat(msg.taskId()).isEqualTo(resp.taskId());
        assertThat(msg.stage()).isEqualTo(TaskStage.PARSE_PAPER);
        assertThat(msg.attempt()).isEqualTo(1);
        assertThat(msg.messageId()).isNotBlank();
        assertThat(msg.requestId()).isNotBlank();

        StageExecution first = stageExecutionMapper.selectOne(new LambdaQueryWrapper<StageExecution>()
                .eq(StageExecution::getTaskId, resp.taskId())
                .eq(StageExecution::getStage, TaskStage.PARSE_PAPER)
                .eq(StageExecution::getAttempt, 1));
        assertThat(first).isNotNull();
        assertThat(msg.stageExecutionId()).isEqualTo(first.getId());
    }

    @Test
    void idempotentRequestKeyDispatchesOnlyOnce() {
        Project project = insertProject();
        File file = insertFile();

        TaskResponse first = taskService.createTask(project.getId(),
                new CreateTaskRequest(file.getId(), null, null, "req-idem"));
        TaskResponse again = taskService.createTask(project.getId(),
                new CreateTaskRequest(file.getId(), null, null, "req-idem"));

        assertThat(again.taskId()).isEqualTo(first.taskId());
        assertThat(taskMapper.selectCount(new LambdaQueryWrapper<AnalysisTask>()
                .eq(AnalysisTask::getRequestKey, "req-idem"))).isEqualTo(1L);
        verify(producer, times(1)).send(any());
    }

    @Test
    void rollbackDoesNotDispatch() {
        TransactionTemplate tx = new TransactionTemplate(transactionManager);
        assertThatThrownBy(() -> tx.executeWithoutResult(status -> {
            eventPublisher.publishEvent(new TaskCreatedEvent(999L, "req-rollback"));
            throw new RuntimeException("force rollback");
        })).isInstanceOf(RuntimeException.class);

        verify(producer, never()).send(any());
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

    private Project insertProject() {
        Project project = new Project();
        project.setName("p");
        projectMapper.insert(project);
        return project;
    }

    private File insertFile() {
        File file = new File();
        file.setFileName("PatchTST.pdf");
        file.setSha256("a".repeat(64));
        file.setSize(100L);
        file.setStoragePath("/tmp/paperpilot/uploads/" + "a".repeat(64) + ".pdf");
        fileMapper.insert(file);
        return file;
    }
}

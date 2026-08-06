package com.paperpilot.api.retry;

import com.baomidou.mybatisplus.core.MybatisConfiguration;
import com.baomidou.mybatisplus.core.metadata.TableInfoHelper;
import com.paperpilot.api.domain.entity.AnalysisTask;
import com.paperpilot.api.domain.entity.StageExecution;
import com.paperpilot.api.domain.enums.StageExecutionStatus;
import com.paperpilot.api.domain.enums.TaskStage;
import com.paperpilot.api.domain.enums.TaskStatus;
import com.paperpilot.api.dto.mq.StageTaskMessage;
import com.paperpilot.api.mapper.AnalysisTaskMapper;
import com.paperpilot.api.mapper.StageExecutionMapper;
import com.paperpilot.api.mq.StageMessageProducer;
import com.paperpilot.api.progress.TaskProgressService;
import com.paperpilot.api.service.TaskEventService;
import org.apache.ibatis.builder.MapperBuilderAssistant;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.transaction.TransactionStatus;
import org.springframework.transaction.support.TransactionCallback;
import org.springframework.transaction.support.TransactionTemplate;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class RetrySchedulerTest {

    @Mock AnalysisTaskMapper taskMapper;
    @Mock StageExecutionMapper stageMapper;
    @Mock StageMessageProducer producer;
    @Mock TaskProgressService progressService;
    @Mock TaskEventService eventService;
    @Mock TransactionTemplate txTemplate;
    @Mock TransactionStatus transactionStatus;

    private RetryScheduler scheduler;

    @BeforeAll
    static void initTableInfo() {
        MapperBuilderAssistant assistant = new MapperBuilderAssistant(new MybatisConfiguration(), "");
        TableInfoHelper.initTableInfo(assistant, StageExecution.class);
        TableInfoHelper.initTableInfo(assistant, AnalysisTask.class);
    }

    @BeforeEach
    void setUp() {
        Clock clock = Clock.fixed(Instant.parse("2026-08-06T00:00:00Z"), ZoneOffset.UTC);
        scheduler = new RetryScheduler(taskMapper, stageMapper, producer, progressService, eventService,
                new RetryProperties(4, 100, Duration.ofSeconds(5)), txTemplate, clock);
        when(txTemplate.execute(any(TransactionCallback.class))).thenAnswer(inv -> {
            @SuppressWarnings("unchecked")
            TransactionCallback<Object> callback = inv.getArgument(0);
            return callback.doInTransaction(transactionStatus);
        });
    }

    @Test
    void dueRetryCreatesNextAttemptAndDispatchesMatchingMessage() {
        StageExecution waiting = waitingStage();
        when(stageMapper.selectList(any())).thenReturn(List.of(waiting));
        when(taskMapper.selectById(7L)).thenReturn(waitingTask());
        when(taskMapper.update(any(), any())).thenReturn(1);
        when(stageMapper.update(any(), any())).thenReturn(1);
        when(stageMapper.insert(any(StageExecution.class))).thenAnswer(inv -> {
            StageExecution inserted = inv.getArgument(0);
            inserted.setId(35L);
            return 1;
        });
        when(progressService.stageStart(TaskStage.PARSE_PAPER)).thenReturn(0);

        scheduler.scanDueRetries();

        ArgumentCaptor<StageExecution> stageCaptor = ArgumentCaptor.forClass(StageExecution.class);
        verify(stageMapper).insert(stageCaptor.capture());
        StageExecution next = stageCaptor.getValue();
        assertThat(next.getAttempt()).isEqualTo(2);
        assertThat(next.getStatus()).isEqualTo(StageExecutionStatus.PENDING);
        assertThat(next.getInputSnapshot()).isEqualTo(waiting.getInputSnapshot());

        ArgumentCaptor<StageTaskMessage> messageCaptor = ArgumentCaptor.forClass(StageTaskMessage.class);
        verify(producer).send(messageCaptor.capture());
        assertThat(messageCaptor.getValue().stageExecutionId()).isEqualTo(35L);
        assertThat(messageCaptor.getValue().attempt()).isEqualTo(2);
    }

    @Test
    void cancelledTaskIsNeverRevivedOrDispatched() {
        when(stageMapper.selectList(any())).thenReturn(List.of(waitingStage()));
        AnalysisTask cancelled = waitingTask();
        cancelled.setStatus(TaskStatus.CANCELLED);
        when(taskMapper.selectById(7L)).thenReturn(cancelled);

        scheduler.scanDueRetries();

        verify(taskMapper, never()).update(any(), any());
        verify(stageMapper, never()).insert(any(StageExecution.class));
        verify(producer, never()).send(any());
    }

    @Test
    void losingConditionalClaimDoesNotCreateOrDispatch() {
        when(stageMapper.selectList(any())).thenReturn(List.of(waitingStage()));
        when(taskMapper.selectById(7L)).thenReturn(waitingTask());
        when(taskMapper.update(any(), any())).thenReturn(1);
        when(stageMapper.update(any(), any())).thenReturn(0);

        scheduler.scanDueRetries();

        verify(transactionStatus).setRollbackOnly();
        verify(stageMapper, never()).insert(any(StageExecution.class));
        verify(producer, never()).send(any());
    }

    private AnalysisTask waitingTask() {
        AnalysisTask task = new AnalysisTask();
        task.setId(7L);
        task.setStatus(TaskStatus.WAITING_RETRY);
        task.setVersion(2);
        return task;
    }

    private StageExecution waitingStage() {
        StageExecution stage = new StageExecution();
        stage.setId(34L);
        stage.setTaskId(7L);
        stage.setStage(TaskStage.PARSE_PAPER);
        stage.setAttempt(1);
        stage.setStatus(StageExecutionStatus.WAITING_RETRY);
        stage.setInputSnapshot("{\"schemaVersion\":1}");
        stage.setNextRetryAt(LocalDateTime.of(2026, 8, 5, 23, 59));
        stage.setVersion(2);
        return stage;
    }
}

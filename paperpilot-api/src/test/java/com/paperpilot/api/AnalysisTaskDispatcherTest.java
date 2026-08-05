package com.paperpilot.api;

import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import com.paperpilot.api.domain.entity.AnalysisTask;
import com.paperpilot.api.domain.entity.StageExecution;
import com.paperpilot.api.domain.enums.StageExecutionStatus;
import com.paperpilot.api.domain.enums.TaskStage;
import com.paperpilot.api.dto.mq.StageTaskMessage;
import com.paperpilot.api.mapper.AnalysisTaskMapper;
import com.paperpilot.api.mq.AnalysisTaskDispatcher;
import com.paperpilot.api.mq.StageDispatchException;
import com.paperpilot.api.mq.StageMessageProducer;
import com.paperpilot.api.service.StageExecutionService;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.slf4j.LoggerFactory;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 首阶段派发器单元测试：选最早的 PENDING 阶段（新任务即 PARSE_PAPER）、
 * 无 PENDING/任务不存在跳过、发送失败记录标识信息并吞掉（不标记成功）.
 */
@ExtendWith(MockitoExtension.class)
class AnalysisTaskDispatcherTest {

    @Mock
    AnalysisTaskMapper taskMapper;
    @Mock
    StageExecutionService stageExecutionService;
    @Mock
    StageMessageProducer producer;

    private AnalysisTaskDispatcher dispatcher() {
        return new AnalysisTaskDispatcher(taskMapper, stageExecutionService, producer);
    }

    @Test
    void dispatchesEarliestPendingStageWithCorrectIds() {
        when(taskMapper.selectById(7L)).thenReturn(task());
        when(stageExecutionService.listByTask(7L)).thenReturn(List.of(
                stage(34L, TaskStage.PARSE_PAPER, StageExecutionStatus.PENDING),
                stage(35L, TaskStage.CLONE_REPOSITORY, StageExecutionStatus.PENDING),
                stage(36L, TaskStage.INDEX_CODE, StageExecutionStatus.PENDING),
                stage(37L, TaskStage.MAP_CONCEPTS, StageExecutionStatus.PENDING)));

        dispatcher().dispatchFirstStage(7L);

        ArgumentCaptor<StageTaskMessage> captor = ArgumentCaptor.forClass(StageTaskMessage.class);
        verify(producer).send(captor.capture());
        StageTaskMessage msg = captor.getValue();
        assertThat(msg.schemaVersion()).isEqualTo(StageTaskMessage.SCHEMA_VERSION);
        assertThat(msg.taskId()).isEqualTo(7L);
        assertThat(msg.stageExecutionId()).isEqualTo(34L);   // 第一条 PARSE_PAPER
        assertThat(msg.stage()).isEqualTo(TaskStage.PARSE_PAPER);
        assertThat(msg.attempt()).isEqualTo(1);
        assertThat(msg.messageId()).isNotBlank();
        assertThat(msg.requestId()).isNotBlank();
    }

    @Test
    void skipsWhenNoPendingStage() {
        when(taskMapper.selectById(7L)).thenReturn(task());
        when(stageExecutionService.listByTask(7L)).thenReturn(List.of(
                stage(34L, TaskStage.PARSE_PAPER, StageExecutionStatus.RUNNING),
                stage(35L, TaskStage.CLONE_REPOSITORY, StageExecutionStatus.SUCCEEDED)));

        dispatcher().dispatchFirstStage(7L);

        verify(producer, never()).send(any());
    }

    @Test
    void skipsWhenTaskNotFound() {
        when(taskMapper.selectById(7L)).thenReturn(null);

        dispatcher().dispatchFirstStage(7L);

        verify(producer, never()).send(any());
        verify(stageExecutionService, never()).listByTask(any());
    }

    @Test
    void sendFailureIsLoggedWithIdentifiersAndSwallowed() {
        when(taskMapper.selectById(7L)).thenReturn(task());
        when(stageExecutionService.listByTask(7L)).thenReturn(List.of(
                stage(34L, TaskStage.PARSE_PAPER, StageExecutionStatus.PENDING)));
        doThrow(new StageDispatchException("broker down")).when(producer).send(any());

        Logger logger = (Logger) LoggerFactory.getLogger(AnalysisTaskDispatcher.class);
        ListAppender<ILoggingEvent> appender = new ListAppender<>();
        appender.start();
        logger.addAppender(appender);
        try {
            // 发送失败被吞掉：调用不抛异常，任务保持 QUEUED 不标记成功
            assertThatCode(() -> dispatcher().dispatchFirstStage(7L)).doesNotThrowAnyException();
        } finally {
            logger.detachAppender(appender);
        }

        verify(producer).send(any());
        List<String> messages = appender.list.stream().map(ILoggingEvent::getFormattedMessage).toList();
        assertThat(messages).anyMatch(m -> m.contains("taskId=7")
                && m.contains("stageExecutionId=34")
                && m.contains("messageId=")
                && m.contains("requestId="));
        // 只记录标识信息，不输出完整消息 JSON payload
        assertThat(messages).noneMatch(m -> m.contains("\"taskId\""));
    }

    private AnalysisTask task() {
        AnalysisTask task = new AnalysisTask();
        task.setId(7L);
        return task;
    }

    private StageExecution stage(long id, TaskStage stage, StageExecutionStatus status) {
        StageExecution e = new StageExecution();
        e.setId(id);
        e.setStage(stage);
        e.setStatus(status);
        e.setAttempt(1);
        return e;
    }
}

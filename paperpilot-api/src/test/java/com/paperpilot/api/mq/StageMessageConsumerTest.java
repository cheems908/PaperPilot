package com.paperpilot.api.mq;

import com.paperpilot.api.domain.enums.TaskStage;
import com.paperpilot.api.dto.mq.StageTaskMessage;
import com.paperpilot.api.service.StageExecutionResult;
import com.paperpilot.api.service.StageOrchestrator;
import com.paperpilot.api.service.StageProgressionService;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.Instant;

import static org.assertj.core.api.Assertions.assertThatCode;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 阶段消费者单测：合法消息成功 → 编排器 + 推进；非法/失败/跳过 → ACK 且不推进（避免无限重投）.
 */
@ExtendWith(MockitoExtension.class)
class StageMessageConsumerTest {

    @Mock
    StageOrchestrator orchestrator;
    @Mock
    StageProgressionService progressionService;

    private StageMessageConsumer consumer() {
        return new StageMessageConsumer(orchestrator, progressionService);
    }

    @Test
    void validMessageCallsOrchestratorAndAdvancesOnSuccess() throws Exception {
        StageTaskMessage message = message(TaskStage.PARSE_PAPER);
        when(orchestrator.orchestrate(message))
                .thenReturn(new StageExecutionResult(34L, true, true, false, null));

        consumer().onMessage(StageTaskMessage.toJson(message));

        verify(orchestrator).orchestrate(message);
        verify(progressionService).advance(message);
    }

    @Test
    void invalidJsonIsAckedWithoutOrchestrator() {
        assertThatCode(() -> consumer().onMessage("{not json")).doesNotThrowAnyException();
        verify(orchestrator, never()).orchestrate(any());
        verify(progressionService, never()).advance(any());
    }

    @Test
    void nonMvpStageMessageIsRejectedAndAcked() throws Exception {
        StageTaskMessage message = new StageTaskMessage(StageTaskMessage.SCHEMA_VERSION, "m-1", "r",
                7L, 34L, TaskStage.GENERATE_REPORT, 1, Instant.now());

        assertThatCode(() -> consumer().onMessage(StageTaskMessage.toJson(message))).doesNotThrowAnyException();

        verify(orchestrator, never()).orchestrate(any());
        verify(progressionService, never()).advance(any());
    }

    @Test
    void failedResultIsAckedWithoutAdvancing() throws Exception {
        StageTaskMessage message = message(TaskStage.PARSE_PAPER);
        when(orchestrator.orchestrate(message))
                .thenReturn(new StageExecutionResult(34L, true, false, false, "TIMEOUT"));

        consumer().onMessage(StageTaskMessage.toJson(message));

        verify(orchestrator).orchestrate(message);
        verify(progressionService, never()).advance(any());
    }

    @Test
    void skippedResultIsAckedWithoutAdvancing() throws Exception {
        StageTaskMessage message = message(TaskStage.PARSE_PAPER);
        when(orchestrator.orchestrate(message))
                .thenReturn(new StageExecutionResult(34L, false, false, true, "幂等跳过"));

        consumer().onMessage(StageTaskMessage.toJson(message));

        verify(orchestrator).orchestrate(message);
        verify(progressionService, never()).advance(any());
    }

    private StageTaskMessage message(TaskStage stage) {
        return new StageTaskMessage(StageTaskMessage.SCHEMA_VERSION, "m-1", "req-trace",
                7L, 34L, stage, 1, Instant.now());
    }
}

package com.paperpilot.api.service;

import com.paperpilot.api.common.ApiException;
import com.paperpilot.api.common.ErrorCode;
import com.paperpilot.api.domain.entity.AnalysisTask;
import com.paperpilot.api.domain.enums.TaskStatus;
import com.paperpilot.api.dto.task.TaskDetailResponse;
import com.paperpilot.api.mapper.AnalysisTaskMapper;
import com.paperpilot.api.mapper.FileMapper;
import com.paperpilot.api.mapper.GitRepositoryMapper;
import com.paperpilot.api.mapper.PaperMapper;
import com.paperpilot.api.mapper.ProjectMapper;
import com.paperpilot.api.progress.TaskProgressService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.context.ApplicationEventPublisher;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * AnalysisTaskService 取消/重试语义单元测试（Mockito，无 Spring/DB）：
 * 可取消状态、重复取消幂等、非法迁移业务码、并发迁移只有一个更新成功.
 */
@ExtendWith(MockitoExtension.class)
class AnalysisTaskServiceTest {

    @Mock
    AnalysisTaskMapper analysisTaskMapper;
    @Mock
    ProjectMapper projectMapper;
    @Mock
    FileMapper fileMapper;
    @Mock
    PaperMapper paperMapper;
    @Mock
    GitRepositoryMapper repositoryMapper;
    @Mock
    StageExecutionService stageExecutionService;
    @Mock
    TaskEventService taskEventService;
    @Mock
    TaskProgressService progressService;
    @Mock
    ApplicationEventPublisher eventPublisher;

    AnalysisTaskService service;

    @BeforeEach
    void setUp() {
        service = new AnalysisTaskService(analysisTaskMapper, projectMapper, fileMapper,
                paperMapper, repositoryMapper, stageExecutionService, taskEventService,
                progressService, eventPublisher);
    }

    // ── 取消 ──────────────────────────────────────────────────────────────

    @Test
    void cancelsFromQueuedRunningAndWaitingRetry() {
        for (TaskStatus from : new TaskStatus[]{TaskStatus.QUEUED, TaskStatus.RUNNING, TaskStatus.WAITING_RETRY}) {
            AnalysisTask task = task(7L, from);
            when(analysisTaskMapper.selectById(7L)).thenReturn(task);
            // MyBatis-Plus updateById(T) 与 updateById(Collection<T>) 重载在 when() 内歧义，
            // 用 doReturn().when(mock) 形式在具体接收者上解析。
            doReturn(1).when(analysisTaskMapper).updateById(task);

            TaskDetailResponse resp = service.cancel(7L);

            assertThat(resp.status()).isEqualTo("CANCELLED");
            assertThat(task.getStatus()).isEqualTo(TaskStatus.CANCELLED);
        }
        verify(stageExecutionService, times(3)).cancelPendingStages(7L);
        verify(taskEventService, times(3)).publish(eq(7L), any(), any());
    }

    @Test
    void repeatedCancelIsIdempotent() {
        AnalysisTask task = task(7L, TaskStatus.CANCELLED);
        when(analysisTaskMapper.selectById(7L)).thenReturn(task);

        assertThat(service.cancel(7L).status()).isEqualTo("CANCELLED");

        verify(analysisTaskMapper, never()).updateById(any(AnalysisTask.class));
        verify(stageExecutionService, never()).cancelPendingStages(any());
        verify(taskEventService, never()).publish(anyLong(), any(), any());
    }

    @Test
    void cancelOnSucceededOrFailedThrowsIllegalTransition() {
        for (TaskStatus from : new TaskStatus[]{TaskStatus.SUCCEEDED, TaskStatus.FAILED}) {
            AnalysisTask task = task(7L, from);
            when(analysisTaskMapper.selectById(7L)).thenReturn(task);

            assertThatThrownBy(() -> service.cancel(7L))
                    .isInstanceOf(ApiException.class)
                    .satisfies(e -> assertThat(((ApiException) e).getCode())
                            .isEqualTo(ErrorCode.ILLEGAL_TASK_TRANSITION.getCode()));
        }
        verify(analysisTaskMapper, never()).updateById(any(AnalysisTask.class));
    }

    @Test
    void concurrentCancelOnlyOneUpdateSucceeds() {
        // 模拟两个并发读到的同一行（version 相同）：一次成功，一次 0 行更新 → CONFLICT
        AnalysisTask first = task(7L, TaskStatus.QUEUED);
        AnalysisTask second = task(7L, TaskStatus.QUEUED);
        when(analysisTaskMapper.selectById(7L)).thenReturn(first, second);
        doReturn(1, 0).when(analysisTaskMapper).updateById(any(AnalysisTask.class));

        assertThat(service.cancel(7L).status()).isEqualTo("CANCELLED");
        assertThatThrownBy(() -> service.cancel(7L))
                .isInstanceOf(ApiException.class)
                .satisfies(e -> assertThat(((ApiException) e).getCode())
                        .isEqualTo(ErrorCode.CONFLICT.getCode()));
        verify(stageExecutionService).cancelPendingStages(7L);
    }

    // ── 重试（仅 FAILED → QUEUED）─────────────────────────────────────────

    @Test
    void retryFromFailedGoesToQueued() {
        AnalysisTask task = task(7L, TaskStatus.FAILED);
        when(analysisTaskMapper.selectById(7L)).thenReturn(task);
        doReturn(1).when(analysisTaskMapper).updateById(task);

        TaskDetailResponse resp = service.retry(7L);

        assertThat(resp.status()).isEqualTo("QUEUED");
        assertThat(task.getStatus()).isEqualTo(TaskStatus.QUEUED);
        verify(stageExecutionService).resetForRetry(7L);
        verify(taskEventService).publish(eq(7L), any(), any());
    }

    @Test
    void retryRejectsNonFailedStatuses() {
        for (TaskStatus from : new TaskStatus[]{TaskStatus.PENDING, TaskStatus.QUEUED, TaskStatus.RUNNING,
                TaskStatus.WAITING_RETRY, TaskStatus.SUCCEEDED, TaskStatus.CANCELLED}) {
            AnalysisTask task = task(7L, from);
            when(analysisTaskMapper.selectById(7L)).thenReturn(task);

            assertThatThrownBy(() -> service.retry(7L))
                    .isInstanceOf(ApiException.class)
                    .satisfies(e -> assertThat(((ApiException) e).getCode())
                            .isEqualTo(ErrorCode.ILLEGAL_TASK_TRANSITION.getCode()));
        }
        verify(analysisTaskMapper, never()).updateById(any(AnalysisTask.class));
        verify(stageExecutionService, never()).resetForRetry(any());
    }

    @Test
    void concurrentRetryOnlyOneUpdateSucceeds() {
        AnalysisTask first = task(7L, TaskStatus.FAILED);
        AnalysisTask second = task(7L, TaskStatus.FAILED);
        when(analysisTaskMapper.selectById(7L)).thenReturn(first, second);
        doReturn(1, 0).when(analysisTaskMapper).updateById(any(AnalysisTask.class));

        assertThat(service.retry(7L).status()).isEqualTo("QUEUED");
        assertThatThrownBy(() -> service.retry(7L))
                .isInstanceOf(ApiException.class)
                .satisfies(e -> assertThat(((ApiException) e).getCode())
                        .isEqualTo(ErrorCode.CONFLICT.getCode()));
        verify(stageExecutionService).resetForRetry(7L);
    }

    private AnalysisTask task(long id, TaskStatus status) {
        AnalysisTask task = new AnalysisTask();
        task.setId(id);
        task.setStatus(status);
        return task;
    }
}

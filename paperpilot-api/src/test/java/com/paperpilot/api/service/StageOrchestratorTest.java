package com.paperpilot.api.service;

import com.baomidou.mybatisplus.core.MybatisConfiguration;
import com.baomidou.mybatisplus.core.metadata.TableInfoHelper;
import com.paperpilot.api.domain.entity.AnalysisTask;
import com.paperpilot.api.domain.entity.StageExecution;
import com.paperpilot.api.domain.enums.StageExecutionStatus;
import com.paperpilot.api.domain.enums.TaskStage;
import com.paperpilot.api.domain.enums.TaskStatus;
import com.paperpilot.api.dto.mq.StageTaskMessage;
import com.paperpilot.api.dto.worker.WorkerStageRequest;
import com.paperpilot.api.dto.worker.WorkerStageResponse;
import com.paperpilot.api.mapper.AnalysisTaskMapper;
import com.paperpilot.api.mapper.StageExecutionMapper;
import com.paperpilot.api.worker.WorkerClient;
import com.paperpilot.api.worker.WorkerErrorCode;
import com.paperpilot.api.worker.WorkerException;
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

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.function.Consumer;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 阶段编排器流程逻辑单测（Mockito）：引用校验、幂等、抢占失败、Worker 成功/失败、
 * 结果落库失败、请求组装.
 */
@ExtendWith(MockitoExtension.class)
class StageOrchestratorTest {

    @Mock
    AnalysisTaskMapper taskMapper;
    @Mock
    StageExecutionMapper stageExecutionMapper;
    @Mock
    WorkerClient workerClient;
    @Mock
    TransactionTemplate txTemplate;

    StageOrchestrator orchestrator;

    @BeforeAll
    static void initMybatisPlusTableInfo() {
        // Mock 环境下无 SqlSessionFactory，LambdaUpdateWrapper 需要手动填充 TableInfo 缓存
        MapperBuilderAssistant assistant = new MapperBuilderAssistant(new MybatisConfiguration(), "");
        TableInfoHelper.initTableInfo(assistant, StageExecution.class);
        TableInfoHelper.initTableInfo(assistant, AnalysisTask.class);
    }

    @BeforeEach
    void setUp() {
        orchestrator = new StageOrchestrator(taskMapper, stageExecutionMapper, workerClient, txTemplate);
        // 让 mock 事务直接执行回调体（只测流程逻辑，事务语义由集成测试覆盖）。
        // lenient：提前返回的用例不会触及事务桩，避免 UnnecessaryStubbingException。
        lenient().when(txTemplate.execute(any(TransactionCallback.class))).thenAnswer(inv -> {
            @SuppressWarnings("unchecked")
            TransactionCallback<Boolean> cb = inv.getArgument(0);
            return cb.doInTransaction(null);
        });
        lenient().doAnswer(inv -> {
            @SuppressWarnings("unchecked")
            Consumer<TransactionStatus> cb = inv.getArgument(0);
            cb.accept(null);
            return null;
        }).when(txTemplate).executeWithoutResult(any(Consumer.class));
    }

    // ── 引用 / 幂等：不调用 Worker ───────────────────────────────────────

    @Test
    void missingTaskDoesNotCallWorker() {
        when(taskMapper.selectById(7L)).thenReturn(null);

        StageExecutionResult r = orchestrator.orchestrate(message(7L, 34L, TaskStage.PARSE_PAPER, 1));

        assertThat(r.skipped()).isTrue();
        verify(workerClient, never()).execute(any());
    }

    @Test
    void referenceMismatchDoesNotCallWorker() {
        when(taskMapper.selectById(7L)).thenReturn(task(TaskStatus.QUEUED));
        when(stageExecutionMapper.selectById(34L))
                .thenReturn(stage(34L, 7L, TaskStage.PARSE_PAPER, 2, StageExecutionStatus.PENDING));

        StageExecutionResult r = orchestrator.orchestrate(message(7L, 34L, TaskStage.PARSE_PAPER, 1));

        assertThat(r.skipped()).isTrue();
        assertThat(r.detail()).contains("不一致");
        verify(workerClient, never()).execute(any());
    }

    @Test
    void terminalTaskDoesNotCallWorker() {
        when(taskMapper.selectById(7L)).thenReturn(task(TaskStatus.SUCCEEDED));
        when(stageExecutionMapper.selectById(34L))
                .thenReturn(stage(34L, 7L, TaskStage.PARSE_PAPER, 1, StageExecutionStatus.PENDING));

        StageExecutionResult r = orchestrator.orchestrate(message(7L, 34L, TaskStage.PARSE_PAPER, 1));

        assertThat(r.skipped()).isTrue();
        assertThat(r.detail()).contains("SUCCEEDED");
        verify(workerClient, never()).execute(any());
    }

    @Test
    void succeededStageDoesNotCallWorker() {
        when(taskMapper.selectById(7L)).thenReturn(task(TaskStatus.RUNNING));
        when(stageExecutionMapper.selectById(34L))
                .thenReturn(stage(34L, 7L, TaskStage.PARSE_PAPER, 1, StageExecutionStatus.SUCCEEDED));

        StageExecutionResult r = orchestrator.orchestrate(message(7L, 34L, TaskStage.PARSE_PAPER, 1));

        assertThat(r.skipped()).isTrue();
        assertThat(r.detail()).contains("SUCCEEDED");
        verify(workerClient, never()).execute(any());
    }

    // ── 抢占失败：不调用 Worker ──────────────────────────────────────────

    @Test
    void claimFailureDoesNotCallWorker() {
        when(taskMapper.selectById(7L)).thenReturn(task(TaskStatus.QUEUED));
        when(stageExecutionMapper.selectById(34L))
                .thenReturn(stage(34L, 7L, TaskStage.PARSE_PAPER, 1, StageExecutionStatus.PENDING));
        when(stageExecutionMapper.update(any(), any())).thenReturn(0); // 抢占 0 行

        StageExecutionResult r = orchestrator.orchestrate(message(7L, 34L, TaskStage.PARSE_PAPER, 1));

        assertThat(r.skipped()).isTrue();
        assertThat(r.detail()).contains("未获得执行权");
        verify(workerClient, never()).execute(any());
    }

    // ── Worker 成功 ──────────────────────────────────────────────────────

    @Test
    void workerSuccessCallsOnceAndReturnsSuccess() {
        when(taskMapper.selectById(7L)).thenReturn(task(TaskStatus.QUEUED));
        when(stageExecutionMapper.selectById(34L)).thenReturn(stageWithInput(34L));
        when(stageExecutionMapper.update(any(), any())).thenReturn(1); // claim + save 均成功
        when(workerClient.execute(any())).thenReturn(successResponse("0.1"));

        StageExecutionResult r = orchestrator.orchestrate(message(7L, 34L, TaskStage.PARSE_PAPER, 1));

        assertThat(r.success()).isTrue();
        assertThat(r.skipped()).isFalse();
        verify(workerClient, times(1)).execute(any());
        verify(stageExecutionMapper, times(2)).update(any(), any()); // claim + save
    }

    @Test
    void workerRequestCarriesIdentifiersAndLoadedInput() {
        when(taskMapper.selectById(7L)).thenReturn(task(TaskStatus.RUNNING));
        when(stageExecutionMapper.selectById(34L)).thenReturn(stageWithInput(34L));
        when(stageExecutionMapper.update(any(), any())).thenReturn(1);
        when(workerClient.execute(any())).thenReturn(successResponse("0.1"));

        orchestrator.orchestrate(message(7L, 34L, TaskStage.PARSE_PAPER, 1));

        ArgumentCaptor<WorkerStageRequest> captor = ArgumentCaptor.forClass(WorkerStageRequest.class);
        verify(workerClient).execute(captor.capture());
        WorkerStageRequest req = captor.getValue();
        assertThat(req.schemaVersion()).isEqualTo(WorkerStageRequest.SCHEMA_VERSION);
        assertThat(req.requestId()).isEqualTo("req-trace");
        assertThat(req.taskId()).isEqualTo(7L);
        assertThat(req.stageExecutionId()).isEqualTo(34L);
        assertThat(req.stage()).isEqualTo(TaskStage.PARSE_PAPER);
        assertThat(req.attempt()).isEqualTo(1);
        // input 从 stage 的输入快照解析而来
        assertThat(req.input()).isNotNull();
        assertThat(req.input().toString()).contains("\"fileId\":1");
    }

    // ── Worker 失败 / 结果落库失败 ──────────────────────────────────────

    @Test
    void workerFailureWritesErrorSnapshotAndReturnsFailed() {
        when(taskMapper.selectById(7L)).thenReturn(task(TaskStatus.RUNNING));
        when(stageExecutionMapper.selectById(34L))
                .thenReturn(stage(34L, 7L, TaskStage.PARSE_PAPER, 1, StageExecutionStatus.PENDING));
        when(stageExecutionMapper.update(any(), any())).thenReturn(1); // claim 成功
        // 远端错误码 INVALID_PDF 透传到失败结果（而非笼统的 HTTP_5XX）
        doThrow(new WorkerException(WorkerErrorCode.HTTP_5XX, 500, "INVALID_PDF", false, "not a pdf", null))
                .when(workerClient).execute(any());

        StageExecutionResult r = orchestrator.orchestrate(message(7L, 34L, TaskStage.PARSE_PAPER, 1));

        assertThat(r.success()).isFalse();
        assertThat(r.detail()).contains("INVALID_PDF").doesNotContain("HTTP_5XX");
        verify(stageExecutionMapper, times(2)).update(any(), any()); // claim + saveFailure
        // 任务已 RUNNING，仅 saveFailure 触发任务 RUNNING→FAILED 更新
        verify(taskMapper, times(1)).update(any(), any());
    }

    @Test
    void resultSaveFailureDoesNotReturnSuccess() {
        when(taskMapper.selectById(7L)).thenReturn(task(TaskStatus.RUNNING));
        when(stageExecutionMapper.selectById(34L))
                .thenReturn(stage(34L, 7L, TaskStage.PARSE_PAPER, 1, StageExecutionStatus.PENDING));
        when(stageExecutionMapper.update(any(), any())).thenReturn(1, 0); // claim 成功，save 0 行
        when(workerClient.execute(any())).thenReturn(successResponse("0.1"));

        StageExecutionResult r = orchestrator.orchestrate(message(7L, 34L, TaskStage.PARSE_PAPER, 1));

        assertThat(r.success()).isFalse();
        assertThat(r.detail()).contains("RESULT_SAVE_FAILED");
        verify(workerClient, times(1)).execute(any());
    }

    // ── helpers ──────────────────────────────────────────────────────────

    private StageTaskMessage message(long taskId, long stageExecutionId, TaskStage stage, int attempt) {
        return new StageTaskMessage(StageTaskMessage.SCHEMA_VERSION, "m-1", "req-trace",
                taskId, stageExecutionId, stage, attempt, Instant.parse("2026-08-04T12:00:00Z"));
    }

    private AnalysisTask task(TaskStatus status) {
        AnalysisTask t = new AnalysisTask();
        t.setId(7L);
        t.setStatus(status);
        t.setVersion(1);
        return t;
    }

    private StageExecution stage(long id, long taskId, TaskStage stage, int attempt, StageExecutionStatus status) {
        StageExecution s = new StageExecution();
        s.setId(id);
        s.setTaskId(taskId);
        s.setStage(stage);
        s.setAttempt(attempt);
        s.setStatus(status);
        s.setVersion(1);
        return s;
    }

    private StageExecution stageWithInput(long id) {
        StageExecution s = stage(id, 7L, TaskStage.PARSE_PAPER, 1, StageExecutionStatus.PENDING);
        s.setInputSnapshot("{\"schemaVersion\":1,\"taskId\":7,\"stage\":\"PARSE_PAPER\","
                + "\"source\":{\"fileId\":1,\"storagePath\":\"/tmp/p.pdf\"}}");
        return s;
    }

    private WorkerStageResponse successResponse(String workerVersion) {
        return new WorkerStageResponse(WorkerStageResponse.SCHEMA_VERSION, true,
                Map.of("title", "t"), List.of(), Map.of("pages", 5), workerVersion);
    }
}

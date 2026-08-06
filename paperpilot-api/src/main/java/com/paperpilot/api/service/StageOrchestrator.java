package com.paperpilot.api.service;

import com.baomidou.mybatisplus.core.conditions.update.LambdaUpdateWrapper;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.paperpilot.api.common.RequestId;
import com.paperpilot.api.domain.TaskStateMachine;
import com.paperpilot.api.domain.entity.AnalysisTask;
import com.paperpilot.api.domain.entity.StageExecution;
import com.paperpilot.api.domain.enums.StageExecutionStatus;
import com.paperpilot.api.domain.enums.TaskStage;
import com.paperpilot.api.domain.enums.TaskStatus;
import com.paperpilot.api.dto.mq.StageTaskMessage;
import com.paperpilot.api.dto.snapshot.StageErrorSnapshot;
import com.paperpilot.api.dto.snapshot.StageOutputSnapshot;
import com.paperpilot.api.dto.snapshot.StageSnapshotCodec;
import com.paperpilot.api.dto.snapshot.StageSnapshotContract;
import com.paperpilot.api.dto.worker.WorkerStageRequest;
import com.paperpilot.api.dto.worker.WorkerStageResponse;
import com.paperpilot.api.mapper.AnalysisTaskMapper;
import com.paperpilot.api.mapper.StageExecutionMapper;
import com.paperpilot.api.progress.TaskProgressService;
import com.paperpilot.api.recovery.StageHeartbeatService;
import com.paperpilot.api.retry.RetryPolicy;
import com.paperpilot.api.retry.StageErrorClassifier;
import com.paperpilot.api.retry.StageFailureOutcome;
import com.paperpilot.api.worker.WorkerClient;
import com.paperpilot.api.worker.WorkerException;
import lombok.RequiredArgsConstructor;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
import org.springframework.transaction.support.TransactionTemplate;

import java.time.Instant;
import java.time.LocalDateTime;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/**
 * 幂等阶段编排器：单阶段执行事务边界 + 执行权抢占 + Worker 调用 + 结果落库.
 *
 * <p>保证重复消息不会重复执行业务：
 * <ol>
 *   <li>加载 task/stage，校验消息引用与库中记录一致；</li>
 *   <li>task 不可执行或 stage 已 SUCCEEDED/CANCELLED → 幂等 ACK 返回；</li>
 *   <li>短事务内条件 UPDATE 抢占 PENDING/WAITING_RETRY（status IN + version），
 *       影响行数 0 视为未获得执行权，不调用 Worker；必要时 task QUEUED→RUNNING；</li>
 *   <li>事务外调用 {@link WorkerClient}（HTTP 调用不占用长事务）；</li>
 *   <li>短事务内原子保存业务结果（output snapshot）与阶段 SUCCEEDED。</li>
 * </ol>
 *
 * <p>本组件不发送下一阶段消息、不以 Redis 分布式锁为正确性基础；
 * Worker 失败经唯一错误分类器进入 WAITING_RETRY 或最终 FAILED，到期派发由 RetryScheduler 完成。
 */
@Component
@RequiredArgsConstructor
public class StageOrchestrator {

    private static final Logger log = LoggerFactory.getLogger(StageOrchestrator.class);

    private final AnalysisTaskMapper taskMapper;
    private final StageExecutionMapper stageExecutionMapper;
    private final WorkerClient workerClient;
    private final CodeSymbolPersistenceService codeSymbolPersistenceService;
    private final MappingPersistenceService mappingPersistenceService;
    private final TaskProgressService progressService;
    private final TaskEventService taskEventService;
    private final StageErrorClassifier errorClassifier;
    private final RetryPolicy retryPolicy;
    private final StageHeartbeatService heartbeatService;
    private final TransactionTemplate txTemplate;

    /** 幂等编排入口。 */
    public StageExecutionResult orchestrate(StageTaskMessage message) {
        StageExecutionContext ctx = load(message);
        if (ctx == null) {
            log.warn("阶段消息跳过：task/stage 不存在 stageExecutionId={}",
                    message == null ? null : message.stageExecutionId());
            return StageExecutionResult.skipped(null, "task/stage 不存在");
        }
        if (!referencesMatch(ctx)) {
            log.warn("阶段消息跳过：引用与库中记录不一致 stageExecutionId={} stage={} attempt={}",
                    ctx.stage().getId(), ctx.stage().getStage(), ctx.stage().getAttempt());
            return StageExecutionResult.skipped(ctx, "消息引用与库中记录不一致");
        }
        StageExecutionResult early = earlyReturn(ctx);
        if (early != null) {
            return early;
        }

        Boolean claimed = txTemplate.execute(tx -> claim(ctx));
        if (!Boolean.TRUE.equals(claimed)) {
            return StageExecutionResult.skipped(ctx, "未获得执行权（并发消费或阶段不可抢占）");
        }
        // 正式状态（MySQL）已提交，再尽力更新 Redis 进度（best-effort，失败只告警）
        TaskStage stage = ctx.stage().getStage();
        Long taskId = ctx.task().getId();
        progressService.update(taskId, TaskStatus.RUNNING, stage,
                progressService.stageStart(stage), "开始阶段 " + stage);
        taskEventService.publish(taskId, TaskEventType.STAGE_STARTED,
                new TaskEventPayload(TaskStatus.RUNNING.name(), stage.name(),
                        progressService.stageStart(stage), "开始阶段 " + stage));

        if (cancelledAfterClaim(ctx)) {
            cancelRunningStage(ctx);
            return StageExecutionResult.skipped(ctx, "任务已取消，Worker 未调用");
        }

        WorkerStageResponse response;
        try (StageHeartbeatService.HeartbeatLease ignored = heartbeatService.begin(ctx.stage().getId())) {
            if (cancelledAfterClaim(ctx)) {
                cancelRunningStage(ctx);
                return StageExecutionResult.skipped(ctx, "任务已取消，Worker 未调用");
            }
            response = workerClient.execute(buildRequest(ctx));
        } catch (WorkerException e) {
            // 优先透传远端业务错误码（如 INVALID_PDF），缺省用 Java 分类
            String errorCode = e.getRemoteErrorCode() != null
                    ? e.getRemoteErrorCode() : e.getErrorCode().name();
            errorCode = errorClassifier.normalize(errorCode);
            handleFailurePresentation(ctx, errorCode, e.getMessage());
            return StageExecutionResult.failed(ctx, errorCode, e.getMessage());
        } catch (Exception e) {
            handleFailurePresentation(ctx, "UNKNOWN", e.getMessage());
            return StageExecutionResult.failed(ctx, "UNKNOWN", e.getMessage());
        }

        // Worker 返回后再次以 MySQL 为准；取消优先时结果不得提交或推进。
        if (cancelledAfterClaim(ctx)) {
            cancelRunningStage(ctx);
            return StageExecutionResult.skipped(ctx, "任务已取消，Worker 结果已丢弃");
        }

        Boolean saved;
        try {
            saved = txTemplate.execute(tx -> saveSuccess(ctx, response));
        } catch (Exception e) {
            handleFailurePresentation(ctx, "RESULT_SAVE_FAILED", "结果落库失败: " + e.getMessage());
            progressService.update(taskId, TaskStatus.FAILED, stage, 100, "结果落库失败");
            return StageExecutionResult.failed(ctx, "RESULT_SAVE_FAILED", "结果落库失败");
        }
        if (!Boolean.TRUE.equals(saved)) {
            if (cancelledAfterClaim(ctx)) {
                cancelRunningStage(ctx);
                return StageExecutionResult.skipped(ctx, "取消赢得结果提交竞态");
            }
            handleFailurePresentation(ctx, "RESULT_SAVE_FAILED", "阶段非 RUNNING，结果未落库");
            progressService.update(taskId, TaskStatus.FAILED, stage, 100, "结果落库失败");
            return StageExecutionResult.failed(ctx, "RESULT_SAVE_FAILED", "结果落库失败");
        }
        progressService.update(taskId, TaskStatus.RUNNING, stage,
                progressService.stageEnd(stage), stage + " 完成");
        taskEventService.publish(taskId, TaskEventType.STAGE_COMPLETED,
                new TaskEventPayload(TaskStatus.RUNNING.name(), stage.name(),
                        progressService.stageEnd(stage), stage + " 完成"));
        log.info("阶段执行成功 stageExecutionId={} stage={} attempt={}",
                ctx.stage().getId(), ctx.stage().getStage(), ctx.stage().getAttempt());
        return StageExecutionResult.succeeded(ctx);
    }

    // ── 加载 / 校验 / 幂等 ────────────────────────────────────────────────

    private StageExecutionContext load(StageTaskMessage message) {
        if (message == null || message.taskId() == null || message.stageExecutionId() == null) {
            return null;
        }
        AnalysisTask task = taskMapper.selectById(message.taskId());
        if (task == null) {
            return null;
        }
        StageExecution stage = stageExecutionMapper.selectById(message.stageExecutionId());
        if (stage == null || !Objects.equals(task.getId(), stage.getTaskId())) {
            return null;
        }
        return new StageExecutionContext(task, stage, message);
    }

    /** 消息引用的 taskId/stageExecutionId/stage/attempt 必须与库中记录一致。 */
    private boolean referencesMatch(StageExecutionContext ctx) {
        StageTaskMessage msg = ctx.message();
        return Objects.equals(msg.taskId(), ctx.task().getId())
                && Objects.equals(msg.stageExecutionId(), ctx.stage().getId())
                && msg.stage() == ctx.stage().getStage()
                && Objects.equals(msg.attempt(), ctx.stage().getAttempt());
    }

    /** task 不可执行或阶段已终态/成功 → ACK 返回（不调用 Worker）。 */
    private StageExecutionResult earlyReturn(StageExecutionContext ctx) {
        TaskStatus taskStatus = ctx.task().getStatus();
        if (!isTaskRunnable(taskStatus)) {
            return StageExecutionResult.skipped(ctx, "任务状态 " + taskStatus + " 不可执行");
        }
        StageExecutionStatus stageStatus = ctx.stage().getStatus();
        if (stageStatus == StageExecutionStatus.SUCCEEDED
                || stageStatus == StageExecutionStatus.CANCELLED) {
            return StageExecutionResult.skipped(ctx, "阶段已 " + stageStatus);
        }
        return null;
    }

    private static boolean isTaskRunnable(TaskStatus status) {
        return status == TaskStatus.QUEUED || status == TaskStatus.RUNNING
                || status == TaskStatus.WAITING_RETRY;
    }

    // ── 事务1：抢占执行权 ─────────────────────────────────────────────────

    /** 条件 UPDATE 抢占 PENDING/WAITING_RETRY；0 行未获得执行权。 */
    private boolean claim(StageExecutionContext ctx) {
        StageExecution stage = ctx.stage();
        int updated = stageExecutionMapper.update(null, new LambdaUpdateWrapper<StageExecution>()
                .eq(StageExecution::getId, stage.getId())
                .eq(StageExecution::getTaskId, ctx.task().getId())
                .eq(StageExecution::getStage, stage.getStage())
                .eq(StageExecution::getAttempt, stage.getAttempt())
                .in(StageExecution::getStatus, StageExecutionStatus.PENDING, StageExecutionStatus.WAITING_RETRY)
                .eq(StageExecution::getVersion, stage.getVersion())
                .set(StageExecution::getStatus, StageExecutionStatus.RUNNING)
                .set(StageExecution::getVersion, stage.getVersion() + 1)
                .set(StageExecution::getStartedAt, LocalDateTime.now()));
        if (updated == 0) {
            return false; // 未获得执行权
        }
        // 必要时任务 QUEUED→RUNNING（经状态机校验；失败不影响已获得的执行权）
        if (ctx.task().getStatus() == TaskStatus.QUEUED) {
            TaskStateMachine.transition(TaskStatus.QUEUED, TaskStatus.RUNNING);
            taskMapper.update(null, new LambdaUpdateWrapper<AnalysisTask>()
                    .eq(AnalysisTask::getId, ctx.task().getId())
                    .eq(AnalysisTask::getStatus, TaskStatus.QUEUED)
                    .eq(AnalysisTask::getVersion, ctx.task().getVersion())
                    .set(AnalysisTask::getStatus, TaskStatus.RUNNING)
                    .set(AnalysisTask::getVersion, ctx.task().getVersion() + 1));
        }
        return true;
    }

    // ── 事务外：Worker 调用 ───────────────────────────────────────────────

    private WorkerStageRequest buildRequest(StageExecutionContext ctx) {
        String trace = ctx.message().requestId();
        return new WorkerStageRequest(
                WorkerStageRequest.SCHEMA_VERSION,
                (trace == null || trace.isBlank()) ? RequestId.generate() : trace,
                ctx.task().getId(),
                ctx.stage().getId(),
                ctx.stage().getStage(),
                ctx.stage().getAttempt(),
                loadStageInput(ctx.stage()));
    }

    /** 从 stage 的 input snapshot 列加载阶段输入（消息只带标识符）。 */
    private Object loadStageInput(StageExecution stage) {
        String json = stage.getInputSnapshot();
        if (json == null || json.isBlank()) {
            return null;
        }
        try {
            return StageSnapshotContract.MAPPER.readTree(json);
        } catch (JsonProcessingException e) {
            log.warn("阶段输入快照解析失败 stageExecutionId={}", stage.getId(), e);
            return null;
        }
    }

    // ── 事务2：原子落库 ───────────────────────────────────────────────────

    /** 业务结果（output snapshot）与阶段 SUCCEEDED 同事务提交；失败不标 SUCCEEDED。 */
    private boolean saveSuccess(StageExecutionContext ctx, WorkerStageResponse response) {
        // 与 cancel() 锁同一 task 行：先获得锁的一方决定结果提交/取消的合法顺序。
        AnalysisTask lockedTask = taskMapper.selectByIdForUpdate(ctx.task().getId());
        if (lockedTask == null || lockedTask.getStatus() != TaskStatus.RUNNING) {
            return false;
        }
        // INDEX_CODE：符号幂等 upsert 到 code_symbol；MAP_CONCEPTS：映射幂等写 concept_code_mapping；
        // 两者 snapshot 只存摘要（避免 TEXT 溢出）
        String outputJson = switch (ctx.stage().getStage()) {
            case INDEX_CODE -> codeSymbolPersistenceService.persist(ctx.task().getRepositoryId(), response);
            case MAP_CONCEPTS -> mappingPersistenceService.persist(
                    ctx.task().getPaperId(), ctx.task().getRepositoryId(), response);
            default -> buildOutputSnapshotJson(response);
        };
        int updated = stageExecutionMapper.update(null, new LambdaUpdateWrapper<StageExecution>()
                .eq(StageExecution::getId, ctx.stage().getId())
                .eq(StageExecution::getStatus, StageExecutionStatus.RUNNING)
                .set(StageExecution::getStatus, StageExecutionStatus.SUCCEEDED)
                .set(StageExecution::getOutputSnapshot, outputJson)
                .set(StageExecution::getFinishedAt, LocalDateTime.now()));
        return updated == 1;
    }

    private String buildOutputSnapshotJson(WorkerStageResponse response) {
        Map<String, Object> summary = new HashMap<>();
        if (response.output() instanceof Map<?, ?> outputMap) {
            outputMap.forEach((k, v) -> summary.put(String.valueOf(k), v));
        } else if (response.output() != null) {
            summary.put("output", response.output());
        }
        if (response.metrics() != null) {
            summary.put("metrics", response.metrics());
        }
        StageOutputSnapshot snapshot = new StageOutputSnapshot(
                StageSnapshotContract.SCHEMA_VERSION,
                response.workerVersion(),
                response.artifacts() == null ? List.of() : response.artifacts(),
                summary);
        try {
            return StageSnapshotCodec.toJson(snapshot);
        } catch (JsonProcessingException e) {
            throw new RuntimeException("输出快照序列化失败", e);
        }
    }

    private void handleFailurePresentation(StageExecutionContext ctx, String errorCode, String message) {
        StageFailureOutcome outcome = saveFailure(ctx, errorCode, message);
        TaskStage stage = ctx.stage().getStage();
        Long taskId = ctx.task().getId();
        if (taskMapper.selectById(taskId).getStatus() == TaskStatus.CANCELLED) {
            cancelRunningStage(ctx);
            return;
        }
        if (outcome.retryScheduled()) {
            String display = "阶段将在 " + outcome.nextRetryAt() + " 重试: " + errorCode;
            progressService.update(taskId, TaskStatus.WAITING_RETRY, stage,
                    progressService.stageStart(stage), display);
            taskEventService.publish(taskId, TaskEventType.STAGE_RETRYING,
                    new TaskEventPayload(TaskStatus.WAITING_RETRY.name(), stage.name(),
                            progressService.stageStart(stage), display));
        } else {
            progressService.update(taskId, TaskStatus.FAILED, stage, 100, "阶段失败: " + errorCode);
            taskEventService.publish(taskId, TaskEventType.TASK_FAILED,
                    new TaskEventPayload(TaskStatus.FAILED.name(), stage.name(), 100,
                            "阶段失败: " + errorCode));
        }
    }

    /** Worker/落库失败：唯一分类器决定 WAITING_RETRY 或最终 FAILED，并保存错误快照。 */
    private StageFailureOutcome saveFailure(StageExecutionContext ctx, String errorCode, String message) {
        try {
            boolean retryable = errorClassifier.isRetryable(errorCode)
                    && retryPolicy.canRetry(ctx.stage().getAttempt());
            LocalDateTime nextRetryAt = retryable
                    ? LocalDateTime.now().plus(retryPolicy.backoffAfter(ctx.stage().getAttempt()).orElseThrow())
                    : null;
            StageErrorSnapshot error = new StageErrorSnapshot(
                    StageSnapshotContract.SCHEMA_VERSION, errorCode, retryable, message, Instant.now());
            String json = StageSnapshotCodec.toJson(error);
            Boolean saved = txTemplate.execute(tx -> {
                StageExecutionStatus stageTarget = retryable
                        ? StageExecutionStatus.WAITING_RETRY : StageExecutionStatus.FAILED;
                int stageUpdated = stageExecutionMapper.update(null, new LambdaUpdateWrapper<StageExecution>()
                        .eq(StageExecution::getId, ctx.stage().getId())
                        .eq(StageExecution::getStatus, StageExecutionStatus.RUNNING)
                        .set(StageExecution::getStatus, stageTarget)
                        .set(StageExecution::getErrorSnapshot, json)
                        .set(StageExecution::getErrorMessage, message)
                        .set(StageExecution::getNextRetryAt, nextRetryAt)
                        .set(StageExecution::getFinishedAt, LocalDateTime.now()));
                TaskStatus taskTarget = retryable ? TaskStatus.WAITING_RETRY : TaskStatus.FAILED;
                TaskStateMachine.transition(TaskStatus.RUNNING, taskTarget);
                int taskUpdated = taskMapper.update(null, new LambdaUpdateWrapper<AnalysisTask>()
                        .eq(AnalysisTask::getId, ctx.task().getId())
                        .eq(AnalysisTask::getStatus, TaskStatus.RUNNING)
                        .set(AnalysisTask::getStatus, taskTarget));
                if (stageUpdated != 1 || taskUpdated != 1) {
                    tx.setRollbackOnly();
                    return false;
                }
                return true;
            });
            if (!Boolean.TRUE.equals(saved)) {
                log.warn("阶段失败状态未落库（并发状态已变化） stageExecutionId={}", ctx.stage().getId());
                return StageFailureOutcome.terminal();
            }
            return retryable ? StageFailureOutcome.retryAt(nextRetryAt) : StageFailureOutcome.terminal();
        } catch (Exception e) {
            log.error("保存阶段失败结果失败 stageExecutionId={}", ctx.stage().getId(), e);
            return StageFailureOutcome.terminal();
        }
    }

    private boolean cancelledAfterClaim(StageExecutionContext ctx) {
        AnalysisTask current = taskMapper.selectById(ctx.task().getId());
        return current == null || current.getStatus() == TaskStatus.CANCELLED;
    }

    /** 取消胜出后只收口当前 RUNNING 行，不覆盖任务终态。 */
    private void cancelRunningStage(StageExecutionContext ctx) {
        stageExecutionMapper.update(null, new LambdaUpdateWrapper<StageExecution>()
                .eq(StageExecution::getId, ctx.stage().getId())
                .eq(StageExecution::getStatus, StageExecutionStatus.RUNNING)
                .set(StageExecution::getStatus, StageExecutionStatus.CANCELLED)
                .set(StageExecution::getFinishedAt, LocalDateTime.now()));
    }
}

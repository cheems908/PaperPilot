package com.paperpilot.api.recovery;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.conditions.update.LambdaUpdateWrapper;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.paperpilot.api.domain.TaskStateMachine;
import com.paperpilot.api.domain.entity.AnalysisTask;
import com.paperpilot.api.domain.entity.StageExecution;
import com.paperpilot.api.domain.enums.StageExecutionStatus;
import com.paperpilot.api.domain.enums.TaskStatus;
import com.paperpilot.api.dto.mq.StageTaskMessage;
import com.paperpilot.api.dto.snapshot.StageErrorSnapshot;
import com.paperpilot.api.dto.snapshot.StageSnapshotCodec;
import com.paperpilot.api.dto.snapshot.StageSnapshotContract;
import com.paperpilot.api.mapper.AnalysisTaskMapper;
import com.paperpilot.api.mapper.StageExecutionMapper;
import com.paperpilot.api.mq.StageMessageProducer;
import com.paperpilot.api.retry.RetryPolicy;
import com.paperpilot.api.retry.RetryScheduler;
import com.paperpilot.api.service.TaskEventService;
import com.paperpilot.api.service.TaskEventPayload;
import com.paperpilot.api.service.TaskEventType;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;
import org.springframework.transaction.TransactionStatus;
import org.springframework.transaction.support.TransactionTemplate;

import java.time.Clock;
import java.time.Instant;
import java.time.LocalDateTime;
import java.util.List;

/**
 * 从 MySQL 恢复消息丢失的 QUEUED 阶段和执行租约过期的 RUNNING 阶段。
 * 多实例通过状态、version 与超时条件 UPDATE 抢占，不以 Redis 为正确性依据。
 */
@Component
public class TaskRecoveryScheduler {

    static final String QUEUED_MESSAGE_MISSING = "QUEUED_MESSAGE_MISSING";
    static final String EXECUTION_LEASE_EXPIRED = "EXECUTION_LEASE_EXPIRED";

    private static final Logger log = LoggerFactory.getLogger(TaskRecoveryScheduler.class);

    private final AnalysisTaskMapper taskMapper;
    private final StageExecutionMapper stageMapper;
    private final StageMessageProducer producer;
    private final TaskEventService eventService;
    private final RetryScheduler retryScheduler;
    private final RetryPolicy retryPolicy;
    private final RecoveryProperties properties;
    private final TransactionTemplate txTemplate;
    private final Clock clock;

    @Autowired
    public TaskRecoveryScheduler(AnalysisTaskMapper taskMapper,
                                 StageExecutionMapper stageMapper,
                                 StageMessageProducer producer,
                                 TaskEventService eventService,
                                 RetryScheduler retryScheduler,
                                 RetryPolicy retryPolicy,
                                 RecoveryProperties properties,
                                 TransactionTemplate txTemplate) {
        this(taskMapper, stageMapper, producer, eventService, retryScheduler, retryPolicy,
                properties, txTemplate, Clock.systemDefaultZone());
    }

    TaskRecoveryScheduler(AnalysisTaskMapper taskMapper,
                          StageExecutionMapper stageMapper,
                          StageMessageProducer producer,
                          TaskEventService eventService,
                          RetryScheduler retryScheduler,
                          RetryPolicy retryPolicy,
                          RecoveryProperties properties,
                          TransactionTemplate txTemplate,
                          Clock clock) {
        this.taskMapper = taskMapper;
        this.stageMapper = stageMapper;
        this.producer = producer;
        this.eventService = eventService;
        this.retryScheduler = retryScheduler;
        this.retryPolicy = retryPolicy;
        this.properties = properties;
        this.txTemplate = txTemplate;
        this.clock = clock;
    }

    public void recover() {
        LocalDateTime now = LocalDateTime.now(clock);
        recoverQueued(now);
        recoverExpiredRunning(now);
        retryScheduler.scanDueRetries();
    }

    private void recoverQueued(LocalDateTime now) {
        LocalDateTime cutoff = now.minus(properties.queuedTimeout());
        List<StageExecution> candidates = stageMapper.selectList(new LambdaQueryWrapper<StageExecution>()
                .eq(StageExecution::getStatus, StageExecutionStatus.PENDING)
                .le(StageExecution::getUpdatedAt, cutoff)
                .gt(StageExecution::getId, 0L)
                .orderByAsc(StageExecution::getId)
                .last("LIMIT " + properties.batchSize()));
        for (StageExecution stage : candidates) {
            AnalysisTask task = taskMapper.selectById(stage.getTaskId());
            if (task == null || task.getStatus() != TaskStatus.QUEUED) {
                continue;
            }
            int claimed = stageMapper.update(null, new LambdaUpdateWrapper<StageExecution>()
                    .eq(StageExecution::getId, stage.getId())
                    .eq(StageExecution::getStatus, StageExecutionStatus.PENDING)
                    .eq(StageExecution::getVersion, stage.getVersion())
                    .le(StageExecution::getUpdatedAt, cutoff)
                    .set(StageExecution::getVersion, stage.getVersion() + 1));
            if (claimed == 1 && taskMapper.selectById(task.getId()).getStatus() == TaskStatus.QUEUED) {
                publishRecovery(stage, TaskStatus.QUEUED, QUEUED_MESSAGE_MISSING);
                send(stage);
            }
        }
    }

    private void recoverExpiredRunning(LocalDateTime now) {
        LocalDateTime cutoff = now.minus(properties.runningTimeout());
        List<StageExecution> candidates = stageMapper.selectList(new LambdaQueryWrapper<StageExecution>()
                .eq(StageExecution::getStatus, StageExecutionStatus.RUNNING)
                .apply("(heartbeat_at <= {0} OR (heartbeat_at IS NULL AND started_at <= {0}))", cutoff)
                .gt(StageExecution::getId, 0L)
                .orderByAsc(StageExecution::getId)
                .last("LIMIT " + properties.batchSize()));
        for (StageExecution stage : candidates) {
            RecoveryDispatch dispatch = txTemplate.execute(tx -> expireLease(stage, now, cutoff, tx));
            if (dispatch != null) {
                publishRecovery(stage, dispatch.status(), EXECUTION_LEASE_EXPIRED);
            }
        }
    }

    private RecoveryDispatch expireLease(StageExecution stage, LocalDateTime now,
                                         LocalDateTime cutoff, TransactionStatus tx) {
        AnalysisTask task = taskMapper.selectByIdForUpdate(stage.getTaskId());
        if (task == null || task.getStatus() != TaskStatus.RUNNING) {
            return null;
        }
        boolean retryable = retryPolicy.canRetry(stage.getAttempt());
        TaskStatus target = retryable ? TaskStatus.WAITING_RETRY : TaskStatus.FAILED;
        StageExecutionStatus stageTarget = retryable
                ? StageExecutionStatus.WAITING_RETRY : StageExecutionStatus.FAILED;
        LocalDateTime nextRetryAt = retryable
                ? now.plus(retryPolicy.backoffAfter(stage.getAttempt()).orElseThrow()) : null;
        String errorJson;
        try {
            errorJson = StageSnapshotCodec.toJson(new StageErrorSnapshot(
                    StageSnapshotContract.SCHEMA_VERSION, EXECUTION_LEASE_EXPIRED,
                    retryable, "执行心跳超时，等待安全重试", Instant.now(clock)));
        } catch (JsonProcessingException e) {
            throw new IllegalStateException("恢复错误快照序列化失败", e);
        }

        LambdaUpdateWrapper<StageExecution> update = new LambdaUpdateWrapper<StageExecution>()
                .eq(StageExecution::getId, stage.getId())
                .eq(StageExecution::getStatus, StageExecutionStatus.RUNNING)
                .eq(StageExecution::getVersion, stage.getVersion())
                .apply("(heartbeat_at <= {0} OR (heartbeat_at IS NULL AND started_at <= {0}))", cutoff)
                .set(StageExecution::getStatus, stageTarget)
                .set(StageExecution::getErrorSnapshot, errorJson)
                .set(StageExecution::getErrorMessage, "执行心跳超时，等待安全重试")
                .set(StageExecution::getFinishedAt, now)
                .set(StageExecution::getNextRetryAt, nextRetryAt)
                .set(StageExecution::getVersion, stage.getVersion() + 1);
        int stageUpdated = stageMapper.update(null, update);
        TaskStateMachine.transition(TaskStatus.RUNNING, target);
        int taskUpdated = taskMapper.update(null, new LambdaUpdateWrapper<AnalysisTask>()
                .eq(AnalysisTask::getId, task.getId())
                .eq(AnalysisTask::getStatus, TaskStatus.RUNNING)
                .eq(AnalysisTask::getVersion, task.getVersion())
                .set(AnalysisTask::getStatus, target)
                .set(AnalysisTask::getVersion, task.getVersion() + 1));
        if (stageUpdated != 1 || taskUpdated != 1) {
            tx.setRollbackOnly();
            return null;
        }
        return new RecoveryDispatch(target);
    }

    private void publishRecovery(StageExecution stage, TaskStatus status, String reason) {
        eventService.publish(stage.getTaskId(), TaskEventType.STAGE_RECOVERED,
                new TaskEventPayload(status.name(), stage.getStage().name(), null,
                        "恢复阶段 attempt=" + stage.getAttempt(), reason));
    }

    private void send(StageExecution stage) {
        try {
            producer.send(StageTaskMessage.create(
                    stage.getTaskId(), stage.getId(), stage.getStage(), stage.getAttempt()));
        } catch (Exception e) {
            log.error("恢复消息派发失败 taskId={} stageExecutionId={} reason={}",
                    stage.getTaskId(), stage.getId(), QUEUED_MESSAGE_MISSING, e);
        }
    }

    private record RecoveryDispatch(TaskStatus status) {
    }
}

package com.paperpilot.api.retry;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.conditions.update.LambdaUpdateWrapper;
import com.paperpilot.api.domain.TaskStateMachine;
import com.paperpilot.api.domain.entity.AnalysisTask;
import com.paperpilot.api.domain.entity.StageExecution;
import com.paperpilot.api.domain.enums.StageExecutionStatus;
import com.paperpilot.api.domain.enums.TaskStatus;
import com.paperpilot.api.dto.mq.StageTaskMessage;
import com.paperpilot.api.mapper.AnalysisTaskMapper;
import com.paperpilot.api.mapper.StageExecutionMapper;
import com.paperpilot.api.mq.StageMessageProducer;
import com.paperpilot.api.progress.TaskProgressService;
import com.paperpilot.api.service.TaskEventPayload;
import com.paperpilot.api.service.TaskEventService;
import com.paperpilot.api.service.TaskEventType;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;
import org.springframework.transaction.TransactionStatus;
import org.springframework.transaction.support.TransactionTemplate;

import java.time.Clock;
import java.time.LocalDateTime;
import java.util.List;

/**
 * 扫描到期 WAITING_RETRY 阶段并安全创建下一 attempt。
 *
 * <p>多实例正确性只依赖 MySQL 条件更新：先以任务 WAITING_RETRY→QUEUED 和旧阶段
 * WAITING_RETRY→FAILED 抢占，二者任一失败即回滚；成功后在同事务内插入新的 PENDING
 * attempt。MQ 在事务提交后发送，消息仅引用新行。
 */
@Component
public class RetryScheduler {

    private static final Logger log = LoggerFactory.getLogger(RetryScheduler.class);

    private final AnalysisTaskMapper taskMapper;
    private final StageExecutionMapper stageMapper;
    private final StageMessageProducer producer;
    private final TaskProgressService progressService;
    private final TaskEventService eventService;
    private final RetryProperties properties;
    private final TransactionTemplate txTemplate;
    private final Clock clock;

    @Autowired
    public RetryScheduler(AnalysisTaskMapper taskMapper,
                          StageExecutionMapper stageMapper,
                          StageMessageProducer producer,
                          TaskProgressService progressService,
                          TaskEventService eventService,
                          RetryProperties properties,
                          TransactionTemplate txTemplate) {
        this(taskMapper, stageMapper, producer, progressService, eventService,
                properties, txTemplate, Clock.systemDefaultZone());
    }

    RetryScheduler(AnalysisTaskMapper taskMapper,
                   StageExecutionMapper stageMapper,
                   StageMessageProducer producer,
                   TaskProgressService progressService,
                   TaskEventService eventService,
                   RetryProperties properties,
                   TransactionTemplate txTemplate,
                   Clock clock) {
        this.taskMapper = taskMapper;
        this.stageMapper = stageMapper;
        this.producer = producer;
        this.progressService = progressService;
        this.eventService = eventService;
        this.properties = properties;
        this.txTemplate = txTemplate;
        this.clock = clock;
    }

    @Scheduled(fixedDelayString = "${paperpilot.retry.scan-interval:PT5S}")
    public void scanDueRetries() {
        LocalDateTime now = LocalDateTime.now(clock);
        List<StageExecution> due = stageMapper.selectList(new LambdaQueryWrapper<StageExecution>()
                .eq(StageExecution::getStatus, StageExecutionStatus.WAITING_RETRY)
                .le(StageExecution::getNextRetryAt, now)
                .orderByAsc(StageExecution::getNextRetryAt, StageExecution::getId)
                .last("LIMIT " + properties.scanBatchSize()));
        for (StageExecution waiting : due) {
            RetryDispatch dispatch = txTemplate.execute(status -> claimAndCreate(waiting, now, status));
            if (dispatch != null) {
                publishAfterCommit(dispatch);
            }
        }
    }

    private RetryDispatch claimAndCreate(StageExecution waiting, LocalDateTime now, TransactionStatus tx) {
        AnalysisTask task = taskMapper.selectById(waiting.getTaskId());
        if (task == null || task.getStatus() != TaskStatus.WAITING_RETRY) {
            return null; // CANCELLED/终态优先，绝不复活
        }
        TaskStateMachine.transition(TaskStatus.WAITING_RETRY, TaskStatus.QUEUED);
        int taskClaimed = taskMapper.update(null, new LambdaUpdateWrapper<AnalysisTask>()
                .eq(AnalysisTask::getId, task.getId())
                .eq(AnalysisTask::getStatus, TaskStatus.WAITING_RETRY)
                .eq(AnalysisTask::getVersion, task.getVersion())
                .set(AnalysisTask::getStatus, TaskStatus.QUEUED)
                .set(AnalysisTask::getVersion, task.getVersion() + 1));
        if (taskClaimed != 1) {
            return null;
        }

        int stageClaimed = stageMapper.update(null, new LambdaUpdateWrapper<StageExecution>()
                .eq(StageExecution::getId, waiting.getId())
                .eq(StageExecution::getStatus, StageExecutionStatus.WAITING_RETRY)
                .eq(StageExecution::getAttempt, waiting.getAttempt())
                .eq(StageExecution::getVersion, waiting.getVersion())
                .le(StageExecution::getNextRetryAt, now)
                .set(StageExecution::getStatus, StageExecutionStatus.FAILED)
                .set(StageExecution::getNextRetryAt, null)
                .set(StageExecution::getVersion, waiting.getVersion() + 1));
        if (stageClaimed != 1) {
            tx.setRollbackOnly();
            return null;
        }

        StageExecution next = new StageExecution();
        next.setTaskId(waiting.getTaskId());
        next.setStage(waiting.getStage());
        next.setAttempt(waiting.getAttempt() + 1);
        next.setStatus(StageExecutionStatus.PENDING);
        next.setInputSnapshot(waiting.getInputSnapshot());
        stageMapper.insert(next);
        return new RetryDispatch(waiting.getTaskId(), next.getId(), next.getStage(), next.getAttempt());
    }

    private void publishAfterCommit(RetryDispatch dispatch) {
        progressService.reset(dispatch.taskId());
        progressService.update(dispatch.taskId(), TaskStatus.QUEUED, dispatch.stage(),
                progressService.stageStart(dispatch.stage()), "重试已入队，attempt=" + dispatch.attempt());
        eventService.publish(dispatch.taskId(), TaskEventType.STAGE_RETRYING,
                new TaskEventPayload(TaskStatus.QUEUED.name(), dispatch.stage().name(),
                        progressService.stageStart(dispatch.stage()), "重试已入队，attempt=" + dispatch.attempt()));
        StageTaskMessage message = StageTaskMessage.create(dispatch.taskId(), dispatch.stageExecutionId(),
                dispatch.stage(), dispatch.attempt());
        try {
            producer.send(message);
        } catch (Exception e) {
            log.error("重试消息派发失败 taskId={} stageExecutionId={} attempt={}",
                    dispatch.taskId(), dispatch.stageExecutionId(), dispatch.attempt(), e);
        }
    }

    private record RetryDispatch(Long taskId, Long stageExecutionId,
                                 com.paperpilot.api.domain.enums.TaskStage stage, Integer attempt) {
    }
}

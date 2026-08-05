package com.paperpilot.api.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.conditions.update.LambdaUpdateWrapper;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.paperpilot.api.domain.TaskStateMachine;
import com.paperpilot.api.domain.entity.AnalysisTask;
import com.paperpilot.api.domain.entity.StageExecution;
import com.paperpilot.api.domain.enums.TaskStage;
import com.paperpilot.api.domain.enums.TaskStatus;
import com.paperpilot.api.dto.mq.StageTaskMessage;
import com.paperpilot.api.dto.snapshot.StageSnapshotContract;
import com.paperpilot.api.mapper.AnalysisTaskMapper;
import com.paperpilot.api.mapper.StageExecutionMapper;
import com.paperpilot.api.mq.StageMessageProducer;
import lombok.RequiredArgsConstructor;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
import org.springframework.transaction.support.TransactionTemplate;

import java.time.LocalDateTime;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * 阶段推进：当前阶段成功提交后，初始化下一阶段输入快照并派发下一消息；
 * 最后阶段成功后把任务迁移 {@code SUCCEEDED} 并记录 {@code finishedAt}.
 *
 * <p>幂等推进：下一阶段输入快照用条件更新（{@code input_snapshot IS NULL}）原子抢占，
 * 同一成功回调两次时第二次返回 0 行 → 不重复派发；任务终态判定（RUNNING→SUCCEEDED）
 * 同样条件更新，重复调用无副作用。
 *
 * <p>已知一致性窗口：输入快照已写而 MQ send 前进程崩溃 → 下一阶段不会被派发，
 * 由 T7 Outbox/恢复扫描兜底。
 */
@Component
@RequiredArgsConstructor
public class StageProgressionService {

    private static final Logger log = LoggerFactory.getLogger(StageProgressionService.class);

    private final NextStageResolver nextStageResolver;
    private final AnalysisTaskMapper taskMapper;
    private final StageExecutionMapper stageExecutionMapper;
    private final StageMessageProducer stageMessageProducer;
    private final TransactionTemplate txTemplate;

    /** 推进到下一阶段或完成整个任务（当前阶段已由编排器标记 SUCCEEDED）。 */
    public void advance(StageTaskMessage message) {
        AnalysisTask task = taskMapper.selectById(message.taskId());
        if (task == null || task.getStatus() != TaskStatus.RUNNING) {
            log.info("推进跳过：任务非 RUNNING taskId={} status={}",
                    message.taskId(), task == null ? null : task.getStatus());
            return; // CANCELLED/FAILED/SUCCEEDED 等不派发下一阶段
        }
        TaskStage next = nextStageResolver.nextOf(message.stage());
        if (next == null) {
            completeTaskIfRunning(message.taskId());
            return;
        }
        Boolean initialized = txTemplate.execute(tx -> initNextInputSnapshot(message, next));
        if (!Boolean.TRUE.equals(initialized)) {
            log.info("推进跳过：下一阶段输入快照已初始化（重复推进）taskId={} next={}",
                    message.taskId(), next);
            return;
        }
        dispatchNext(message, next);
    }

    private boolean initNextInputSnapshot(StageTaskMessage message, TaskStage next) {
        String json = buildNextInputSnapshot(message, next);
        int updated = stageExecutionMapper.update(null, new LambdaUpdateWrapper<StageExecution>()
                .eq(StageExecution::getTaskId, message.taskId())
                .eq(StageExecution::getStage, next)
                .eq(StageExecution::getAttempt, 1)
                .isNull(StageExecution::getInputSnapshot)
                .set(StageExecution::getInputSnapshot, json));
        return updated == 1;
    }

    /** 下一阶段输入快照：引用上一阶段执行（具体输入语义由 T3 细化）。 */
    private String buildNextInputSnapshot(StageTaskMessage message, TaskStage next) {
        Map<String, Object> input = new LinkedHashMap<>();
        input.put("schemaVersion", StageSnapshotContract.SCHEMA_VERSION);
        input.put("taskId", message.taskId());
        input.put("stage", next.name());
        input.put("upstreamStageExecutionId", message.stageExecutionId());
        try {
            return StageSnapshotContract.MAPPER.writeValueAsString(input);
        } catch (JsonProcessingException e) {
            throw new RuntimeException("输入快照序列化失败", e);
        }
    }

    private void dispatchNext(StageTaskMessage message, TaskStage next) {
        StageExecution nextStage = stageExecutionMapper.selectOne(
                new LambdaQueryWrapper<StageExecution>()
                        .eq(StageExecution::getTaskId, message.taskId())
                        .eq(StageExecution::getStage, next)
                        .eq(StageExecution::getAttempt, 1)
                        .last("LIMIT 1"));
        if (nextStage == null) {
            log.error("派发下一阶段失败：下一阶段行不存在 taskId={} next={}", message.taskId(), next);
            return;
        }
        StageTaskMessage nextMessage = StageTaskMessage.create(
                message.taskId(), nextStage.getId(), next, nextStage.getAttempt());
        try {
            stageMessageProducer.send(nextMessage);
        } catch (Exception e) {
            // 只记录标识信息；一致性窗口（输入快照已写而消息未发）由 T7 Outbox 兜底
            log.error("派发下一阶段失败 taskId={} nextStageExecutionId={} next={}",
                    message.taskId(), nextStage.getId(), next, e);
        }
    }

    /** 最后阶段：任务 RUNNING→SUCCEEDED + finishedAt（条件更新防重复）。 */
    private void completeTaskIfRunning(Long taskId) {
        TaskStateMachine.transition(TaskStatus.RUNNING, TaskStatus.SUCCEEDED);
        txTemplate.executeWithoutResult(tx -> taskMapper.update(null,
                new LambdaUpdateWrapper<AnalysisTask>()
                        .eq(AnalysisTask::getId, taskId)
                        .eq(AnalysisTask::getStatus, TaskStatus.RUNNING)
                        .set(AnalysisTask::getStatus, TaskStatus.SUCCEEDED)
                        .set(AnalysisTask::getFinishedAt, LocalDateTime.now())));
    }
}

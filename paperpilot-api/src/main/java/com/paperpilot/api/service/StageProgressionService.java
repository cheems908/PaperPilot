package com.paperpilot.api.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.conditions.update.LambdaUpdateWrapper;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.paperpilot.api.domain.TaskStateMachine;
import com.paperpilot.api.domain.entity.AnalysisTask;
import com.paperpilot.api.domain.entity.GitRepository;
import com.paperpilot.api.domain.entity.StageExecution;
import com.paperpilot.api.domain.enums.TaskStage;
import com.paperpilot.api.domain.enums.TaskStatus;
import com.paperpilot.api.dto.mq.StageTaskMessage;
import com.paperpilot.api.dto.snapshot.StageSnapshotContract;
import com.paperpilot.api.mapper.AnalysisTaskMapper;
import com.paperpilot.api.mapper.FileMapper;
import com.paperpilot.api.mapper.GitRepositoryMapper;
import com.paperpilot.api.mapper.StageExecutionMapper;
import com.paperpilot.api.mq.StageMessageProducer;
import com.paperpilot.api.progress.TaskProgressService;
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
    private final GitRepositoryMapper repositoryMapper;
    private final FileMapper fileMapper;
    private final StageMessageProducer stageMessageProducer;
    private final TaskProgressService progressService;
    private final TaskEventService taskEventService;
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

    /** 下一阶段输入快照：组装真实 Worker 契约，所有数据均来自 DB 固化状态。 */
    private String buildNextInputSnapshot(StageTaskMessage message, TaskStage next) {
        try {
            ObjectNode input = StageSnapshotContract.MAPPER.createObjectNode();
            input.put("schemaVersion", StageSnapshotContract.SCHEMA_VERSION);
            input.put("taskId", message.taskId());
            input.put("stage", next.name());
            input.put("upstreamStageExecutionId", message.stageExecutionId());
            switch (next) {
                case CLONE_REPOSITORY -> addRepositorySource(input, message.taskId());
                case INDEX_CODE -> input.set("source", outputSummary(message.stageExecutionId()));
                case MAP_CONCEPTS -> addMappingInputs(input, message.taskId(), message.stageExecutionId());
                default -> throw new IllegalArgumentException("unsupported next stage: " + next);
            }
            return StageSnapshotContract.MAPPER.writeValueAsString(input);
        } catch (JsonProcessingException e) {
            throw new RuntimeException("输入快照序列化失败", e);
        }
    }

    private void addRepositorySource(ObjectNode input, Long taskId) {
        AnalysisTask task = taskMapper.selectById(taskId);
        GitRepository repository = task == null || task.getRepositoryId() == null
                ? null : repositoryMapper.selectById(task.getRepositoryId());
        if (repository == null || repository.getGithubUrl() == null) {
            throw new IllegalStateException("CLONE_REPOSITORY 缺少任务关联仓库 taskId=" + taskId);
        }
        ObjectNode source = input.putObject("source");
        source.put("githubUrl", repository.getGithubUrl());
        if (repository.getBranch() != null && !repository.getBranch().isBlank()) {
            source.put("branch", repository.getBranch());
        }
    }

    private void addMappingInputs(ObjectNode input, Long taskId, Long indexStageExecutionId) {
        JsonNode parse = successfulOutputSummary(taskId, TaskStage.PARSE_PAPER);
        JsonNode paper = parse.get("paper");
        JsonNode index = outputSummary(indexStageExecutionId);
        JsonNode symbols = index.get("symbols");
        if (paper == null || !paper.isObject() || symbols == null || !symbols.isArray()) {
            throw new IllegalStateException("MAP_CONCEPTS 缺少 paper/symbols taskId=" + taskId);
        }
        input.set("paper", paper);
        input.set("symbols", symbols);
        input.set("commitSha", index.path("commitSha"));
        AnalysisTask task = taskMapper.selectById(taskId);
        com.paperpilot.api.domain.entity.File sourceFile = task == null || task.getSourceFileId() == null
                ? null : fileMapper.selectById(task.getSourceFileId());
        if (sourceFile == null || sourceFile.getSha256() == null
                || !sourceFile.getSha256().matches("^[0-9a-f]{64}$")) {
            throw new IllegalStateException("MAP_CONCEPTS 缺少合法 paperSha256 taskId=" + taskId);
        }
        input.put("paperSha256", sourceFile.getSha256());
    }

    private JsonNode successfulOutputSummary(Long taskId, TaskStage stage) {
        StageExecution execution = stageExecutionMapper.selectOne(new LambdaQueryWrapper<StageExecution>()
                .eq(StageExecution::getTaskId, taskId)
                .eq(StageExecution::getStage, stage)
                .eq(StageExecution::getStatus, com.paperpilot.api.domain.enums.StageExecutionStatus.SUCCEEDED)
                .orderByDesc(StageExecution::getAttempt)
                .last("LIMIT 1"));
        if (execution == null) {
            throw new IllegalStateException("缺少已完成阶段 taskId=" + taskId + " stage=" + stage);
        }
        return outputSummary(execution.getId());
    }

    private JsonNode outputSummary(Long stageExecutionId) {
        StageExecution execution = stageExecutionMapper.selectById(stageExecutionId);
        if (execution == null || execution.getOutputSnapshot() == null) {
            throw new IllegalStateException("缺少阶段输出 stageExecutionId=" + stageExecutionId);
        }
        try {
            JsonNode root = StageSnapshotContract.MAPPER.readTree(execution.getOutputSnapshot());
            // PARSE/CLONE 使用统一 StageOutputSnapshot；INDEX/MAP 持久化服务直接保存摘要。
            JsonNode summary = root.has("summary") ? root.get("summary") : root;
            if (summary == null || !summary.isObject()) {
                throw new IllegalStateException("阶段输出缺少 summary stageExecutionId=" + stageExecutionId);
            }
            return summary;
        } catch (JsonProcessingException e) {
            throw new IllegalStateException("阶段输出 JSON 无效 stageExecutionId=" + stageExecutionId, e);
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
        // 终态写 100% 并刷新 TTL（best-effort）
        progressService.update(taskId, TaskStatus.SUCCEEDED, null, 100, "任务完成");
        taskEventService.publish(taskId, TaskEventType.TASK_COMPLETED,
                new TaskEventPayload(TaskStatus.SUCCEEDED.name(), null, 100, "任务完成"));
    }
}

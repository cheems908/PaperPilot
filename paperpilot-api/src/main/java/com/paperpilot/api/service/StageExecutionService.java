package com.paperpilot.api.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.paperpilot.api.domain.entity.StageExecution;
import com.paperpilot.api.domain.enums.StageExecutionStatus;
import com.paperpilot.api.domain.enums.TaskStage;
import com.paperpilot.api.dto.task.StageResponse;
import com.paperpilot.api.mapper.StageExecutionMapper;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.List;

/**
 * 单阶段执行服务.
 *
 * <p>任务创建时按 {@link TaskStage#MVP_STAGES} 顺序插入初始 4 阶段行
 * （attempt=1, status=PENDING）。重试会新增 attempt+1 的行（表唯一键
 * {@code uk_stage_task_stage_attempt} 约束 task_id+stage+attempt 组合唯一）。
 */
@Service
@RequiredArgsConstructor
public class StageExecutionService {

    private final StageExecutionMapper stageExecutionMapper;

    /** 为任务创建初始阶段行（MVP 前 4 阶段，按流水线顺序）。 */
    public List<StageExecution> createInitialStages(Long taskId) {
        List<StageExecution> list = new ArrayList<>(TaskStage.MVP_STAGES.size());
        for (TaskStage stage : TaskStage.MVP_STAGES) {
            StageExecution execution = new StageExecution();
            execution.setTaskId(taskId);
            execution.setStage(stage);
            execution.setAttempt(1);
            execution.setStatus(StageExecutionStatus.PENDING);
            stageExecutionMapper.insert(execution);
            list.add(execution);
        }
        return list;
    }

    /** 按创建顺序列出任务的全部阶段执行。 */
    public List<StageExecution> listByTask(Long taskId) {
        return stageExecutionMapper.selectList(
                new LambdaQueryWrapper<StageExecution>()
                        .eq(StageExecution::getTaskId, taskId)
                        .orderByAsc(StageExecution::getId));
    }

    /** 列出任务阶段并转为响应 DTO。 */
    public List<StageResponse> listResponses(Long taskId) {
        return listByTask(taskId).stream().map(this::toResponse).toList();
    }

    private StageResponse toResponse(StageExecution e) {
        return new StageResponse(
                e.getStage().name(),
                e.getAttempt(),
                e.getStatus().name(),
                e.getSnapshot(),
                e.getErrorMessage(),
                e.getUpdatedAt());
    }
}

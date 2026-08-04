package com.paperpilot.api.service;

import com.paperpilot.api.domain.entity.AnalysisTask;
import com.paperpilot.api.domain.entity.GitRepository;
import com.paperpilot.api.domain.entity.Paper;
import com.paperpilot.api.domain.entity.StageExecution;
import com.paperpilot.api.domain.enums.TaskStatus;
import com.paperpilot.api.dto.task.PaperInfo;
import com.paperpilot.api.dto.task.RepositoryInfo;
import com.paperpilot.api.dto.task.StageResponse;
import com.paperpilot.api.dto.task.TaskResultResponse;
import com.paperpilot.api.mapper.GitRepositoryMapper;
import com.paperpilot.api.mapper.PaperMapper;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 任务结果服务：汇总任务状态、关联论文/仓库与各阶段快照.
 *
 * <p>{@code result} 聚合已完成阶段（SUCCEEDED）的 snapshot；MVP 阶段由
 * Worker 执行后填充，当前无 Worker 时为空的 stage→snapshot 映射。
 */
@Service
@RequiredArgsConstructor
public class TaskResultService {

    private final AnalysisTaskService analysisTaskService;
    private final StageExecutionService stageExecutionService;
    private final PaperMapper paperMapper;
    private final GitRepositoryMapper repositoryMapper;

    public TaskResultResponse getResult(Long taskId) {
        AnalysisTask task = analysisTaskService.getTaskOrThrow(taskId);
        List<StageExecution> stages = stageExecutionService.listByTask(taskId);
        List<StageResponse> stageResponses = stages.stream()
                .map(s -> new StageResponse(s.getStage().name(), s.getAttempt(),
                        s.getStatus().name(), s.getSnapshot(), s.getErrorMessage(), s.getUpdatedAt()))
                .toList();

        PaperInfo paper = null;
        if (task.getPaperId() != null) {
            Paper p = paperMapper.selectById(task.getPaperId());
            if (p != null) {
                paper = new PaperInfo(p.getId(), p.getTitle());
            }
        }

        RepositoryInfo repository = null;
        if (task.getRepositoryId() != null) {
            GitRepository r = repositoryMapper.selectById(task.getRepositoryId());
            if (r != null) {
                repository = new RepositoryInfo(r.getId(), r.getGithubUrl());
            }
        }

        // 聚合已完成阶段的快照：stage.name -> snapshot JSON
        Map<String, Object> result = new LinkedHashMap<>();
        for (StageExecution s : stages) {
            if (s.getStatus() == TaskStatus.SUCCEEDED && s.getSnapshot() != null) {
                result.put(s.getStage().name(), s.getSnapshot());
            }
        }

        return new TaskResultResponse(taskId, task.getStatus().name(), paper, repository, stageResponses, result);
    }
}

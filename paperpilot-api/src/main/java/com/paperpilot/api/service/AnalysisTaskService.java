package com.paperpilot.api.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.paperpilot.api.common.ApiException;
import com.paperpilot.api.common.ErrorCode;
import com.paperpilot.api.domain.TaskStateMachine;
import com.paperpilot.api.domain.entity.AnalysisTask;
import com.paperpilot.api.domain.entity.File;
import com.paperpilot.api.domain.entity.GitRepository;
import com.paperpilot.api.domain.entity.Paper;
import com.paperpilot.api.domain.entity.Project;
import com.paperpilot.api.domain.enums.TaskStatus;
import com.paperpilot.api.dto.task.CreateTaskRequest;
import com.paperpilot.api.dto.task.TaskDetailResponse;
import com.paperpilot.api.dto.task.TaskResponse;
import com.paperpilot.api.mapper.AnalysisTaskMapper;
import com.paperpilot.api.mapper.FileMapper;
import com.paperpilot.api.mapper.GitRepositoryMapper;
import com.paperpilot.api.mapper.PaperMapper;
import com.paperpilot.api.mapper.ProjectMapper;
import lombok.RequiredArgsConstructor;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.UUID;

/**
 * 分析总任务服务：创建（幂等）、查询、取消、重试.
 *
 * <p>创建任务流程：
 * <ol>
 *   <li>校验项目存在 + {@code fileId}/{@code githubUrl} 至少其一；</li>
 *   <li>幂等：{@code requestKey} 已存在则直接返回既有任务（并发下靠唯一键兜底）；</li>
 *   <li>有文件时先由 file 创建 paper 行，再写 {@code source_file_id}/{@code paper_id}；
 *       有仓库时创建/复用 repository 行，写 {@code repository_id}；</li>
 *   <li>插入 {@code analysis_task}（status=QUEUED）并创建 4 个初始阶段行。</li>
 * </ol>
 * 状态更新一律先过 {@link TaskStateMachine} 校验，再由 {@code @Version} 乐观锁并发兜底。
 */
@Service
@RequiredArgsConstructor
public class AnalysisTaskService {

    private static final String DEFAULT_BRANCH = "main";

    private final AnalysisTaskMapper analysisTaskMapper;
    private final ProjectMapper projectMapper;
    private final FileMapper fileMapper;
    private final PaperMapper paperMapper;
    private final GitRepositoryMapper repositoryMapper;
    private final StageExecutionService stageExecutionService;
    private final TaskEventService taskEventService;

    /** 创建分析任务，返回 202 响应体（taskId/status/eventsUrl）。 */
    @Transactional
    public TaskResponse createTask(Long projectId, CreateTaskRequest req) {
        boolean hasFile = req.fileId() != null;
        boolean hasRepository = req.githubUrl() != null && !req.githubUrl().isBlank();
        if (!hasFile && !hasRepository) {
            throw new ApiException(ErrorCode.BAD_REQUEST, "任务至少需要上传文件（fileId）或仓库地址（githubUrl）之一");
        }

        // 幂等：requestKey 已存在则直接返回既有任务
        String requestKey = (req.requestKey() == null || req.requestKey().isBlank())
                ? UUID.randomUUID().toString()
                : req.requestKey();
        AnalysisTask existing = findByRequestKey(requestKey);
        if (existing != null) {
            return toResponse(existing);
        }

        Project project = projectMapper.selectById(projectId);
        if (project == null) {
            throw new ApiException(ErrorCode.NOT_FOUND, "项目不存在");
        }

        // 由上传文件解析出 paper 记录（不直接写 fileId 进 paper_id）
        Long sourceFileId = null;
        Long paperId = null;
        if (hasFile) {
            File file = fileMapper.selectById(req.fileId());
            if (file == null) {
                throw new ApiException(ErrorCode.NOT_FOUND, "上传文件不存在");
            }
            sourceFileId = file.getId();
            paperId = createPaperFromFile(projectId, file);
        }

        Long repositoryId = null;
        if (hasRepository) {
            repositoryId = createOrGetRepository(projectId, req.githubUrl(), req.branch());
        }

        AnalysisTask task = new AnalysisTask();
        task.setProjectId(projectId);
        task.setSourceFileId(sourceFileId);
        task.setPaperId(paperId);
        task.setRepositoryId(repositoryId);
        task.setRequestKey(requestKey);
        task.setStatus(TaskStatus.QUEUED);
        try {
            analysisTaskMapper.insert(task);
        } catch (DuplicateKeyException e) {
            // 并发重复提交：唯一键 uk_task_request_key 兜底，返回既有任务
            AnalysisTask concurrent = findByRequestKey(requestKey);
            if (concurrent != null) {
                return toResponse(concurrent);
            }
            throw e;
        }

        stageExecutionService.createInitialStages(task.getId());
        taskEventService.publish(task.getId(),
                TaskEvent.of(task.getId(), task.getStatus().name(), null, "任务已创建，等待执行"));
        return toResponse(task);
    }

    /** 查询任务详情；不存在抛 404。 */
    public TaskDetailResponse getTask(Long taskId) {
        return toDetail(getTaskOrThrow(taskId));
    }

    /** 查询任务实体；不存在抛 404（供编排/事件订阅使用）。 */
    public AnalysisTask getTaskOrThrow(Long taskId) {
        AnalysisTask task = analysisTaskMapper.selectById(taskId);
        if (task == null) {
            throw new ApiException(ErrorCode.NOT_FOUND, "任务不存在");
        }
        return task;
    }

    /**
     * 取消任务：PENDING / QUEUED / RUNNING / WAITING_RETRY 可取消到 CANCELLED。
     * 重复取消幂等（已 CANCELLED 直接返回同一终态）；SUCCEEDED / FAILED 取消视为
     * 非法迁移（ILLEGAL_TASK_TRANSITION）。更新依赖 @Version 乐观锁：并发下只有
     * 一个更新成功，其余抛 CONFLICT。
     */
    @Transactional
    public TaskDetailResponse cancel(Long taskId) {
        AnalysisTask task = getTaskOrThrow(taskId);
        if (task.getStatus() == TaskStatus.CANCELLED) {
            return toDetail(task); // 重复取消幂等，不再发事件/动阶段
        }
        TaskStatus next = requireTransition(task.getStatus(), TaskStatus.CANCELLED);
        task.setStatus(next);
        if (analysisTaskMapper.updateById(task) == 0) {
            throw new ApiException(ErrorCode.CONFLICT, "任务状态已变更，请刷新后重试");
        }
        // 尚未执行的阶段同步标记 CANCELLED（条件更新，仅影响 PENDING/WAITING_RETRY）
        stageExecutionService.cancelPendingStages(taskId);
        taskEventService.publish(taskId, TaskEvent.of(taskId, next.name(), null, "任务已取消"));
        return toDetail(task);
    }

    /**
     * 人工重试：仅 FAILED → QUEUED。重置当前失败阶段的调度信息（见
     * {@link StageExecutionService#resetForRetry}）但保留历史 attempt；
     * 其他状态调用视为非法迁移（ILLEGAL_TASK_TRANSITION）。
     */
    @Transactional
    public TaskDetailResponse retry(Long taskId) {
        AnalysisTask task = getTaskOrThrow(taskId);
        if (task.getStatus() != TaskStatus.FAILED) {
            throw new ApiException(ErrorCode.ILLEGAL_TASK_TRANSITION,
                    "仅 FAILED 状态的任务可人工重试，当前状态 " + task.getStatus());
        }
        TaskStatus next = requireTransition(TaskStatus.FAILED, TaskStatus.QUEUED);
        task.setStatus(next);
        if (analysisTaskMapper.updateById(task) == 0) {
            throw new ApiException(ErrorCode.CONFLICT, "任务状态已变更，请刷新后重试");
        }
        stageExecutionService.resetForRetry(taskId);
        taskEventService.publish(taskId, TaskEvent.of(taskId, next.name(), null, "任务已重新入队"));
        return toDetail(task);
    }

    /** 经状态机校验的迁移；非法迁移抛稳定业务码 ILLEGAL_TASK_TRANSITION。 */
    private TaskStatus requireTransition(TaskStatus from, TaskStatus to) {
        try {
            return TaskStateMachine.transition(from, to);
        } catch (IllegalStateException e) {
            throw new ApiException(ErrorCode.ILLEGAL_TASK_TRANSITION, e.getMessage());
        }
    }

    private Long createPaperFromFile(Long projectId, File file) {
        Paper paper = new Paper();
        paper.setProjectId(projectId);
        paper.setTitle(stripExtension(file.getFileName()));
        paper.setPdfUrl(file.getStoragePath());
        paperMapper.insert(paper);
        return paper.getId();
    }

    private Long createOrGetRepository(Long projectId, String githubUrl, String branch) {
        GitRepository existing = repositoryMapper.selectOne(
                new LambdaQueryWrapper<GitRepository>()
                        .eq(GitRepository::getProjectId, projectId)
                        .eq(GitRepository::getGithubUrl, githubUrl)
                        .last("LIMIT 1"));
        if (existing != null) {
            return existing.getId();
        }
        GitRepository repository = new GitRepository();
        repository.setProjectId(projectId);
        repository.setGithubUrl(githubUrl);
        repository.setBranch((branch == null || branch.isBlank()) ? DEFAULT_BRANCH : branch);
        repositoryMapper.insert(repository);
        return repository.getId();
    }

    private AnalysisTask findByRequestKey(String requestKey) {
        return analysisTaskMapper.selectOne(
                new LambdaQueryWrapper<AnalysisTask>()
                        .eq(AnalysisTask::getRequestKey, requestKey)
                        .last("LIMIT 1"));
    }

    private String stripExtension(String name) {
        int idx = name.lastIndexOf('.');
        return idx > 0 ? name.substring(0, idx) : name;
    }

    private TaskResponse toResponse(AnalysisTask task) {
        return new TaskResponse(task.getId(), task.getStatus().name(),
                "/api/v1/tasks/" + task.getId() + "/events");
    }

    private TaskDetailResponse toDetail(AnalysisTask task) {
        return new TaskDetailResponse(
                task.getId(),
                task.getProjectId(),
                task.getSourceFileId(),
                task.getPaperId(),
                task.getRepositoryId(),
                task.getStatus().name(),
                task.getRequestKey(),
                task.getCreatedAt(),
                task.getUpdatedAt());
    }
}

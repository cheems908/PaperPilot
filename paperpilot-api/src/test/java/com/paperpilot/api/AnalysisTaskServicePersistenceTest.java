package com.paperpilot.api;

import com.paperpilot.api.common.ApiException;
import com.paperpilot.api.common.ErrorCode;
import com.paperpilot.api.domain.entity.AnalysisTask;
import com.paperpilot.api.domain.entity.File;
import com.paperpilot.api.domain.entity.GitRepository;
import com.paperpilot.api.domain.entity.Paper;
import com.paperpilot.api.domain.entity.Project;
import com.paperpilot.api.domain.entity.StageExecution;
import com.paperpilot.api.domain.enums.StageExecutionStatus;
import com.paperpilot.api.domain.enums.TaskStage;
import com.paperpilot.api.domain.enums.TaskStatus;
import com.paperpilot.api.dto.task.CreateTaskRequest;
import com.paperpilot.api.dto.task.TaskDetailResponse;
import com.paperpilot.api.dto.task.TaskResponse;
import com.paperpilot.api.mapper.AnalysisTaskMapper;
import com.paperpilot.api.mapper.FileMapper;
import com.paperpilot.api.mapper.GitRepositoryMapper;
import com.paperpilot.api.mapper.PaperMapper;
import com.paperpilot.api.mapper.ProjectMapper;
import com.paperpilot.api.mapper.StageExecutionMapper;
import com.paperpilot.api.progress.TaskProgressProperties;
import com.paperpilot.api.progress.TaskProgressService;
import com.paperpilot.api.service.AnalysisTaskService;
import com.paperpilot.api.service.StageExecutionService;
import com.paperpilot.api.service.TaskEventService;
import org.apache.ibatis.session.SqlSession;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.support.DefaultListableBeanFactory;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.testcontainers.containers.MySQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import javax.sql.DataSource;
import java.time.LocalDateTime;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * 服务层端到端验证：创建任务（文件→paper、仓库→repository、4 个初始阶段行）、
 * request_key 幂等、取消/重试状态机与乐观锁、非法组合校验.
 */
@Testcontainers
class AnalysisTaskServicePersistenceTest {

    @Container
    static final MySQLContainer<?> MYSQL = new MySQLContainer<>("mysql:8.0");

    @Test
    void createTaskLifecycleAndIdempotency() throws Exception {
        DataSource ds = TestSupport.dataSource(MYSQL);
        Flyway.configure().dataSource(ds).load().migrate();

        try (SqlSession session = TestSupport.buildFactory(ds).openSession(true)) {
            ProjectMapper projectMapper = session.getMapper(ProjectMapper.class);
            FileMapper fileMapper = session.getMapper(FileMapper.class);
            AnalysisTaskMapper taskMapper = session.getMapper(AnalysisTaskMapper.class);
            PaperMapper paperMapper = session.getMapper(PaperMapper.class);
            GitRepositoryMapper repositoryMapper = session.getMapper(GitRepositoryMapper.class);
            StageExecutionMapper stageExecutionMapper = session.getMapper(StageExecutionMapper.class);

            StageExecutionService stageService = new StageExecutionService(stageExecutionMapper);
            // 手工组装（无 Spring 上下文）：事件发布用 no-op，AFTER_COMMIT 派发由 TaskCreatedEventTest 覆盖
            TaskProgressService noopProgress = new TaskProgressService(
                    new DefaultListableBeanFactory().getBeanProvider(StringRedisTemplate.class),
                    new TaskProgressProperties(), taskMapper);
            AnalysisTaskService taskService = new AnalysisTaskService(
                    taskMapper, projectMapper, fileMapper, paperMapper, repositoryMapper,
                    stageService, new TaskEventService(), noopProgress, event -> {
                    });

            Project project = new Project();
            project.setName("p");
            projectMapper.insert(project);

            // 预置一个已上传文件（模拟 FileStorageService 落库结果）
            File file = new File();
            file.setFileName("PatchTST.pdf");
            file.setSha256("a".repeat(64));
            file.setSize(100L);
            file.setStoragePath("/tmp/paperpilot/uploads/" + "a".repeat(64) + ".pdf");
            fileMapper.insert(file);

            // 1) 只传文件：任务 QUEUED，创建 paper（source_file_id 记文件，paper_id 记论文）
            TaskResponse resp = taskService.createTask(project.getId(),
                    new CreateTaskRequest(file.getId(), null, null, "req-file"));
            assertThat(resp.taskId()).isNotNull();
            assertThat(resp.status()).isEqualTo("QUEUED");
            assertThat(resp.eventsUrl()).isEqualTo("/api/v1/tasks/" + resp.taskId() + "/events");

            AnalysisTask task = taskMapper.selectById(resp.taskId());
            assertThat(task.getStatus()).isEqualTo(TaskStatus.QUEUED);
            assertThat(task.getSourceFileId()).isEqualTo(file.getId());
            assertThat(task.getRepositoryId()).isNull();
            assertThat(task.getPaperId()).isNotNull();

            Paper paper = paperMapper.selectById(task.getPaperId());
            assertThat(paper.getTitle()).isEqualTo("PatchTST");
            assertThat(paper.getProjectId()).isEqualTo(project.getId());
            assertThat(paper.getPdfUrl()).isEqualTo(file.getStoragePath());

            // 2) 4 个初始阶段行：attempt=1、PENDING，覆盖 MVP 前 4 阶段
            List<StageExecution> stages = stageService.listByTask(resp.taskId());
            assertThat(stages).hasSize(TaskStage.MVP_STAGES.size());
            assertThat(stages).allMatch(s -> s.getAttempt() == 1 && s.getStatus() == StageExecutionStatus.PENDING);
            assertThat(stages).extracting(StageExecution::getStage)
                    .containsExactlyInAnyOrderElementsOf(TaskStage.MVP_STAGES);
            // 文件任务的 PARSE_PAPER 阶段已固化输入快照（消费方从 DB 加载阶段输入）
            StageExecution parsePaper = stages.stream()
                    .filter(s -> s.getStage() == TaskStage.PARSE_PAPER)
                    .findFirst().orElseThrow();
            assertThat(parsePaper.getInputSnapshot())
                    .contains("\"schemaVersion\":1")
                    .contains("\"storagePath\"")
                    .contains("\"fileId\":" + file.getId());

            // 3) request_key 幂等：同 key 返回同一任务
            TaskResponse again = taskService.createTask(project.getId(),
                    new CreateTaskRequest(file.getId(), null, null, "req-file"));
            assertThat(again.taskId()).isEqualTo(resp.taskId());

            // 4) 非法组合：fileId 与 githubUrl 皆空 → 400
            assertThatThrownBy(() -> taskService.createTask(project.getId(),
                    new CreateTaskRequest(null, null, null, "req-none")))
                    .isInstanceOf(ApiException.class)
                    .hasMessageContaining("至少需要");

            // 5) QUEUED 可取消；未执行的 PENDING 阶段同步标记 CANCELLED
            TaskDetailResponse cancelledQueued = taskService.cancel(resp.taskId());
            assertThat(cancelledQueued.status()).isEqualTo("CANCELLED");
            assertThat(taskMapper.selectById(resp.taskId()).getStatus()).isEqualTo(TaskStatus.CANCELLED);
            assertThat(stageService.listByTask(resp.taskId()))
                    .allMatch(s -> s.getStatus() == StageExecutionStatus.CANCELLED);

            // 6) 重复取消幂等：返回同一终态，不抛异常
            assertThat(taskService.cancel(resp.taskId()).status()).isEqualTo("CANCELLED");

            // 7) CANCELLED 不可重试 → ILLEGAL_TASK_TRANSITION
            assertThatThrownBy(() -> taskService.retry(resp.taskId()))
                    .isInstanceOf(ApiException.class)
                    .extracting(e -> ((ApiException) e).getCode())
                    .isEqualTo(ErrorCode.ILLEGAL_TASK_TRANSITION.getCode());

            // 8) RUNNING → CANCELLED（先模拟 Worker 置 RUNNING）
            TaskResponse runningResp = taskService.createTask(project.getId(),
                    new CreateTaskRequest(file.getId(), null, null, "req-running"));
            AnalysisTask running = taskMapper.selectById(runningResp.taskId());
            running.setStatus(TaskStatus.RUNNING);
            taskMapper.updateById(running);
            assertThat(taskService.cancel(runningResp.taskId()).status()).isEqualTo("CANCELLED");

            // 9) 只传仓库：创建 repository
            TaskResponse repoOnly = taskService.createTask(project.getId(),
                    new CreateTaskRequest(null, "https://github.com/paperpilot/patchtst", null, "req-repo"));
            AnalysisTask repoTask = taskMapper.selectById(repoOnly.taskId());
            assertThat(repoTask.getPaperId()).isNull();
            assertThat(repoTask.getRepositoryId()).isNotNull();
            assertThat(repositoryMapper.selectById(repoTask.getRepositoryId()).getGithubUrl())
                    .isEqualTo("https://github.com/paperpilot/patchtst");

            // 10) 人工重试：FAILED → QUEUED；失败阶段重置为 PENDING、清空调度信息、保留 attempt
            AnalysisTask failedTask = taskMapper.selectById(repoOnly.taskId());
            failedTask.setStatus(TaskStatus.FAILED);
            taskMapper.updateById(failedTask);
            StageExecution failedStage = stageService.listByTask(repoOnly.taskId()).stream()
                    .filter(s -> s.getStage() == TaskStage.CLONE_REPOSITORY)
                    .findFirst().orElseThrow();
            failedStage.setStatus(StageExecutionStatus.FAILED);
            failedStage.setErrorSnapshot("{\"schemaVersion\":1}");
            failedStage.setStartedAt(LocalDateTime.of(2026, 8, 4, 12, 0, 0));
            failedStage.setNextRetryAt(LocalDateTime.of(2026, 8, 4, 12, 30, 0));
            stageExecutionMapper.updateById(failedStage);
            Integer attempt = failedStage.getAttempt();

            TaskDetailResponse retried = taskService.retry(repoOnly.taskId());
            assertThat(retried.status()).isEqualTo("QUEUED");
            assertThat(taskMapper.selectById(repoOnly.taskId()).getStatus()).isEqualTo(TaskStatus.QUEUED);
            StageExecution resetStage = stageExecutionMapper.selectById(failedStage.getId());
            assertThat(resetStage.getStatus()).isEqualTo(StageExecutionStatus.PENDING);
            assertThat(resetStage.getAttempt()).isEqualTo(attempt);
            assertThat(resetStage.getErrorSnapshot()).isNull();
            assertThat(resetStage.getStartedAt()).isNull();
            assertThat(resetStage.getNextRetryAt()).isNull();

            // 11) WAITING_RETRY 不可人工重试（仅 FAILED）→ ILLEGAL_TASK_TRANSITION
            AnalysisTask waiting = taskMapper.selectById(repoOnly.taskId());
            waiting.setStatus(TaskStatus.WAITING_RETRY);
            taskMapper.updateById(waiting);
            assertThatThrownBy(() -> taskService.retry(repoOnly.taskId()))
                    .isInstanceOf(ApiException.class)
                    .extracting(e -> ((ApiException) e).getCode())
                    .isEqualTo(ErrorCode.ILLEGAL_TASK_TRANSITION.getCode());

            // 12) 文件+仓库都传：paper 与 repository 都建立
            TaskResponse both = taskService.createTask(project.getId(),
                    new CreateTaskRequest(file.getId(), "https://github.com/paperpilot/patchtst", null, "req-both"));
            AnalysisTask bothTask = taskMapper.selectById(both.taskId());
            assertThat(bothTask.getPaperId()).isNotNull();
            assertThat(bothTask.getRepositoryId()).isNotNull();
        }
    }
}

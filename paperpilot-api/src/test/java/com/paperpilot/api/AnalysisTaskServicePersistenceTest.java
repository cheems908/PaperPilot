package com.paperpilot.api;

import com.paperpilot.api.common.ApiException;
import com.paperpilot.api.domain.entity.AnalysisTask;
import com.paperpilot.api.domain.entity.File;
import com.paperpilot.api.domain.entity.GitRepository;
import com.paperpilot.api.domain.entity.Paper;
import com.paperpilot.api.domain.entity.Project;
import com.paperpilot.api.domain.entity.StageExecution;
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
import com.paperpilot.api.service.AnalysisTaskService;
import com.paperpilot.api.service.StageExecutionService;
import com.paperpilot.api.service.TaskEventService;
import org.apache.ibatis.session.SqlSession;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.Test;
import org.testcontainers.containers.MySQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import javax.sql.DataSource;
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
            AnalysisTaskService taskService = new AnalysisTaskService(
                    taskMapper, projectMapper, fileMapper, paperMapper, repositoryMapper,
                    stageService, new TaskEventService());

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
            assertThat(stages).allMatch(s -> s.getAttempt() == 1 && s.getStatus() == TaskStatus.PENDING);
            assertThat(stages).extracting(StageExecution::getStage)
                    .containsExactlyInAnyOrderElementsOf(TaskStage.MVP_STAGES);

            // 3) request_key 幂等：同 key 返回同一任务
            TaskResponse again = taskService.createTask(project.getId(),
                    new CreateTaskRequest(file.getId(), null, null, "req-file"));
            assertThat(again.taskId()).isEqualTo(resp.taskId());

            // 4) 非法组合：fileId 与 githubUrl 皆空 → 400
            assertThatThrownBy(() -> taskService.createTask(project.getId(),
                    new CreateTaskRequest(null, null, null, "req-none")))
                    .isInstanceOf(ApiException.class)
                    .hasMessageContaining("至少需要");

            // 5) QUEUED 不允许取消（状态机）→ 409
            assertThatThrownBy(() -> taskService.cancel(resp.taskId()))
                    .isInstanceOf(ApiException.class);

            // 6) RUNNING → CANCELLED（先模拟 Worker 置 RUNNING）
            AnalysisTask running = taskMapper.selectById(resp.taskId());
            running.setStatus(TaskStatus.RUNNING);
            taskMapper.updateById(running);
            TaskDetailResponse cancelled = taskService.cancel(resp.taskId());
            assertThat(cancelled.status()).isEqualTo("CANCELLED");
            assertThat(taskMapper.selectById(resp.taskId()).getStatus()).isEqualTo(TaskStatus.CANCELLED);

            // 7) 终态 CANCELLED 不允许重试 → 409
            assertThatThrownBy(() -> taskService.retry(resp.taskId()))
                    .isInstanceOf(ApiException.class);

            // 8) 只传仓库：创建 repository；WAITING_RETRY → RUNNING 重试成功
            TaskResponse repoOnly = taskService.createTask(project.getId(),
                    new CreateTaskRequest(null, "https://github.com/paperpilot/patchtst", null, "req-repo"));
            AnalysisTask repoTask = taskMapper.selectById(repoOnly.taskId());
            assertThat(repoTask.getPaperId()).isNull();
            assertThat(repoTask.getRepositoryId()).isNotNull();
            assertThat(repositoryMapper.selectById(repoTask.getRepositoryId()).getGithubUrl())
                    .isEqualTo("https://github.com/paperpilot/patchtst");

            AnalysisTask waiting = taskMapper.selectById(repoOnly.taskId());
            waiting.setStatus(TaskStatus.WAITING_RETRY);
            taskMapper.updateById(waiting);
            TaskDetailResponse retried = taskService.retry(repoOnly.taskId());
            assertThat(retried.status()).isEqualTo("RUNNING");

            // 9) 文件+仓库都传：paper 与 repository 都建立
            TaskResponse both = taskService.createTask(project.getId(),
                    new CreateTaskRequest(file.getId(), "https://github.com/paperpilot/patchtst", null, "req-both"));
            AnalysisTask bothTask = taskMapper.selectById(both.taskId());
            assertThat(bothTask.getPaperId()).isNotNull();
            assertThat(bothTask.getRepositoryId()).isNotNull();
        }
    }
}

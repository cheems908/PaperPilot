package com.paperpilot.api;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.paperpilot.api.domain.entity.AnalysisTask;
import com.paperpilot.api.domain.entity.File;
import com.paperpilot.api.domain.entity.GitRepository;
import com.paperpilot.api.domain.entity.Paper;
import com.paperpilot.api.domain.entity.Project;
import com.paperpilot.api.domain.entity.StageExecution;
import com.paperpilot.api.domain.enums.StageExecutionStatus;
import com.paperpilot.api.domain.enums.TaskStage;
import com.paperpilot.api.domain.enums.TaskStatus;
import com.paperpilot.api.dto.mq.StageTaskMessage;
import com.paperpilot.api.dto.task.TaskResultResponse;
import com.paperpilot.api.mapper.AnalysisTaskMapper;
import com.paperpilot.api.mapper.FileMapper;
import com.paperpilot.api.mapper.GitRepositoryMapper;
import com.paperpilot.api.mapper.PaperMapper;
import com.paperpilot.api.mapper.ProjectMapper;
import com.paperpilot.api.mapper.StageExecutionMapper;
import com.paperpilot.api.mq.StageMessageConsumer;
import com.paperpilot.api.mq.StageMessageProducer;
import com.paperpilot.api.service.TaskResultService;
import com.paperpilot.api.worker.WorkerProperties;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Primary;
import org.springframework.http.MediaType;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.transaction.PlatformTransactionManager;
import org.testcontainers.containers.MySQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import javax.sql.DataSource;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.function.BooleanSupplier;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.fail;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doAnswer;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * 假 Worker 全链路集成测试（真实 MySQL + 进程内 MQ 桥 + 假 Worker HTTP 服务）：
 * 创建 202+QUEUED → 四阶段顺序 → 任务 SUCCEEDED → 结果重启后仍可查；
 * 同一消息重复投递 10 次仅执行一次；Worker 不可用时任务不会误标成功.
 *
 * <p>RocketMQ broker 用进程内桥替代（producer → consumer 直接路由），验证的是
 * 完整编排链路（派发/消费/幂等/推进），broker 传输层语义由测试 compose 冒烟覆盖。
 */
@SpringBootTest
@AutoConfigureMockMvc
@Testcontainers
class StageFlowIntegrationTest {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    @Container
    static final MySQLContainer<?> MYSQL = new MySQLContainer<>("mysql:8.0");

    static FakeWorkerServer FAKE_WORKER;

    @MockBean
    StageMessageProducer producer;

    @Autowired
    MockMvc mockMvc;
    @Autowired
    StageMessageConsumer consumer;
    @Autowired
    AnalysisTaskMapper taskMapper;
    @Autowired
    StageExecutionMapper stageExecutionMapper;
    @Autowired
    ProjectMapper projectMapper;
    @Autowired
    FileMapper fileMapper;
    @Autowired
    GitRepositoryMapper repositoryMapper;
    @Autowired
    PaperMapper paperMapper;
    @Autowired
    TaskResultService taskResultService;

    @BeforeAll
    static void startWorkerAndMigrate() {
        FAKE_WORKER = FakeWorkerServer.start();
        Flyway.configure().dataSource(TestSupport.dataSource(MYSQL)).load().migrate();
    }

    @AfterAll
    static void stopWorker() {
        if (FAKE_WORKER != null) {
            FAKE_WORKER.close();
        }
    }

    @BeforeEach
    void bridgeProducerToConsumer() {
        // 进程内 MQ 桥：生产者直接路由到真实消费者（验证完整编排链路，不依赖真实 broker）
        doAnswer(inv -> {
            StageTaskMessage msg = inv.getArgument(0);
            consumer.onMessage(StageTaskMessage.toJson(msg));
            return null;
        }).when(producer).send(any(StageTaskMessage.class));
    }

    // ── 验收：创建 202 + QUEUED → 四阶段顺序 → SUCCEEDED → 结果可查 ─────────

    @Test
    void createReturns202AndFullFlowCompletes() throws Exception {
        Project project = insertProject();
        File file = insertFile();

        MvcResultLite result = postCreateTask(project.getId(), file.getId(),
                "https://github.com/paperpilot/patchtst", "req-e2e");
        assertThat(result.status).isEqualTo(202);
        assertThat(result.statusField).isEqualTo("QUEUED");

        long taskId = result.taskId;
        awaitUntilTrue(() -> taskMapper.selectById(taskId).getStatus() == TaskStatus.SUCCEEDED, Duration.ofSeconds(15));

        AnalysisTask task = taskMapper.selectById(taskId);
        assertThat(task.getStatus()).isEqualTo(TaskStatus.SUCCEEDED);
        assertThat(task.getFinishedAt()).isNotNull();

        // 四阶段顺序正确且各 SUCCEEDED
        List<StageExecution> stages = stagesOf(taskId);
        assertThat(stages).extracting(StageExecution::getStage)
                .containsExactly(TaskStage.PARSE_PAPER, TaskStage.CLONE_REPOSITORY,
                        TaskStage.INDEX_CODE, TaskStage.MAP_CONCEPTS);
        assertThat(stages).allMatch(s -> s.getStatus() == StageExecutionStatus.SUCCEEDED);
        // 每个阶段输出快照已落库（结果从 MySQL 查询一致）
        assertThat(stages).allMatch(s -> s.getOutputSnapshot() != null);
        assertThat(stageBy(taskId, TaskStage.INDEX_CODE).getOutputSnapshot()).contains("symbolCount");
        // 假 Worker 对每个 stageExecutionId 恰好执行一次
        for (StageExecution s : stages) {
            assertThat(FAKE_WORKER.executionCount(s.getId())).as("stage %s", s.getStage()).isEqualTo(1);
        }
    }

    // ── 验收：重复投递 10 次仅一次有效执行 ────────────────────────────────

    @Test
    void duplicateDeliveryExecutesWorkerOncePerStage() throws Exception {
        long taskId = insertRunningTaskWithStages();
        StageExecution parse = stageBy(taskId, TaskStage.PARSE_PAPER);
        StageTaskMessage message = StageTaskMessage.create(taskId, parse.getId(), TaskStage.PARSE_PAPER, 1);
        String payload = StageTaskMessage.toJson(message);

        for (int i = 0; i < 10; i++) {
            consumer.onMessage(payload);
        }

        // 假 Worker 只实际执行一次（其余 9 次被幂等吸收）
        assertThat(FAKE_WORKER.executionCount(parse.getId())).isEqualTo(1);
        assertThat(stageExecutionMapper.selectById(parse.getId()).getStatus())
                .isEqualTo(StageExecutionStatus.SUCCEEDED);
        // 链路最终仍推进到 SUCCEEDED（不因重复投递损坏）
        assertThat(taskMapper.selectById(taskId).getStatus()).isEqualTo(TaskStatus.SUCCEEDED);
    }

    // ── 验收：Worker 不可用不误标成功 ─────────────────────────────────────

    @Test
    void workerUnavailableDoesNotMarkSuccess() throws Exception {
        long taskId = insertRunningTaskWithStages();
        StageExecution parse = stageBy(taskId, TaskStage.PARSE_PAPER);
        FAKE_WORKER.setUnavailable(true);
        try {
            consumer.onMessage(StageTaskMessage.toJson(
                    StageTaskMessage.create(taskId, parse.getId(), TaskStage.PARSE_PAPER, 1)));

            AnalysisTask task = taskMapper.selectById(taskId);
            assertThat(task.getStatus()).isNotEqualTo(TaskStatus.SUCCEEDED);
            assertThat(task.getStatus()).isEqualTo(TaskStatus.FAILED);
            StageExecution stage = stageExecutionMapper.selectById(parse.getId());
            assertThat(stage.getStatus()).isEqualTo(StageExecutionStatus.FAILED);
            // 结构化错误快照可查询（worker 503 → HTTP_5XX）
            assertThat(stage.getErrorSnapshot()).contains("HTTP_5XX");
        } finally {
            FAKE_WORKER.setUnavailable(false);
        }
    }

    // ── 验收：Java 重启后已完成结果仍可查 ─────────────────────────────────

    @Test
    void completedResultsQueryableAfterRestart() throws Exception {
        Project project = insertProject();
        File file = insertFile();
        MvcResultLite result = postCreateTask(project.getId(), file.getId(),
                "https://github.com/paperpilot/patchtst", "req-restart");
        long taskId = result.taskId;
        awaitUntilTrue(() -> taskMapper.selectById(taskId).getStatus() == TaskStatus.SUCCEEDED, Duration.ofSeconds(15));

        // 应用状态全部在 MySQL：重启后以全新查询读取即恢复（应用对结果无内存态）
        TaskResultResponse resp = taskResultService.getResult(taskId);
        assertThat(resp.status()).isEqualTo("SUCCEEDED");
        assertThat(resp.stages()).allMatch(s -> "SUCCEEDED".equals(s.status()));
        List<StageExecution> stages = stagesOf(taskId);
        assertThat(stages).allMatch(s -> s.getStatus() == StageExecutionStatus.SUCCEEDED
                && s.getOutputSnapshot() != null);
    }

    // ── helpers ──────────────────────────────────────────────────────────

    private MvcResultLite postCreateTask(long projectId, long fileId, String githubUrl,
                                         String requestKey) throws Exception {
        MvcResultLite out = new MvcResultLite();
        var body = MAPPER.createObjectNode()
                .put("fileId", fileId)
                .put("githubUrl", githubUrl)
                .put("requestKey", requestKey);
        mockMvc.perform(post("/api/v1/projects/{projectId}/analysis-tasks", projectId)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body.toString()))
                .andExpect(status().isAccepted())
                .andExpect(jsonPath("$.code").value(0))
                .andExpect(jsonPath("$.data.status").value("QUEUED"))
                .andDo(mvc -> {
                    var node = MAPPER.readTree(mvc.getResponse().getContentAsString()).path("data");
                    out.taskId = node.path("taskId").asLong();
                    out.statusField = node.path("status").asText();
                    out.status = mvc.getResponse().getStatus();
                });
        return out;
    }

    private long insertRunningTaskWithStages() {
        Project project = new Project();
        project.setName("p");
        projectMapper.insert(project);

        // INDEX/MAP 阶段分别把符号/映射持久化（依赖 repository_id 与 paper_id），故任务需带两者
        GitRepository repo = new GitRepository();
        repo.setProjectId(project.getId());
        repo.setGithubUrl("https://github.com/paperpilot/patchtst");
        repositoryMapper.insert(repo);

        Paper paper = new Paper();
        paper.setProjectId(project.getId());
        paper.setTitle("PatchTST");
        paper.setPdfUrl("http://example.com/paper.pdf");
        paperMapper.insert(paper);

        AnalysisTask task = new AnalysisTask();
        task.setProjectId(project.getId());
        task.setRepositoryId(repo.getId());
        task.setPaperId(paper.getId());
        task.setStatus(TaskStatus.RUNNING);
        task.setRequestKey("req-" + UUID.randomUUID());
        taskMapper.insert(task);

        for (TaskStage stage : TaskStage.MVP_STAGES) {
            StageExecution s = new StageExecution();
            s.setTaskId(task.getId());
            s.setStage(stage);
            s.setAttempt(1);
            s.setStatus(StageExecutionStatus.PENDING);
            stageExecutionMapper.insert(s);
        }
        return task.getId();
    }

    private List<StageExecution> stagesOf(long taskId) {
        return stageExecutionMapper.selectList(new LambdaQueryWrapper<StageExecution>()
                .eq(StageExecution::getTaskId, taskId)
                .orderByAsc(StageExecution::getId));
    }

    private StageExecution stageBy(long taskId, TaskStage stage) {
        return stageExecutionMapper.selectOne(new LambdaQueryWrapper<StageExecution>()
                .eq(StageExecution::getTaskId, taskId)
                .eq(StageExecution::getStage, stage)
                .eq(StageExecution::getAttempt, 1));
    }

    private Project insertProject() {
        Project project = new Project();
        project.setName("p");
        projectMapper.insert(project);
        return project;
    }

    private File insertFile() {
        File file = new File();
        file.setFileName("PatchTST.pdf");
        file.setSha256("a".repeat(64));
        file.setSize(100L);
        file.setStoragePath("/tmp/paperpilot/uploads/" + "a".repeat(64) + ".pdf");
        fileMapper.insert(file);
        return file;
    }

    private static void awaitUntilTrue(BooleanSupplier condition, Duration timeout) {
        long deadline = System.nanoTime() + timeout.toNanos();
        while (System.nanoTime() < deadline) {
            if (condition.getAsBoolean()) {
                return;
            }
            try {
                Thread.sleep(50);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                fail("等待被打断");
            }
        }
        fail("等待超时: " + timeout);
    }

    /** MockMvc 响应中提取的轻量结果。 */
    private static final class MvcResultLite {
        int status;
        long taskId;
        String statusField;
    }

    @TestConfiguration
    static class DbConfig {

        @Bean
        @Primary
        DataSource dataSource() {
            return TestSupport.dataSource(MYSQL);
        }

        @Bean
        @Primary
        WorkerProperties workerProperties() {
            // 指向进程内假 Worker（@BeforeAll 已启动）
            return new WorkerProperties(FAKE_WORKER.baseUrl(),
                    Duration.ofSeconds(3), Duration.ofSeconds(10), Map.of());
        }

        @Bean
        PlatformTransactionManager transactionManager(DataSource dataSource) {
            return new DataSourceTransactionManager(dataSource);
        }
    }
}

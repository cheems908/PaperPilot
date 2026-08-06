package com.paperpilot.api;

import com.paperpilot.api.domain.entity.AnalysisTask;
import com.paperpilot.api.domain.entity.GitRepository;
import com.paperpilot.api.domain.entity.Paper;
import com.paperpilot.api.domain.entity.Project;
import com.paperpilot.api.domain.entity.StageExecution;
import com.paperpilot.api.domain.enums.StageExecutionStatus;
import com.paperpilot.api.domain.enums.TaskStage;
import com.paperpilot.api.domain.enums.TaskStatus;
import com.paperpilot.api.dto.mq.StageTaskMessage;
import com.paperpilot.api.dto.progress.TaskProgressSnapshot;
import com.paperpilot.api.dto.progress.TaskProgressView;
import com.paperpilot.api.mapper.AnalysisTaskMapper;
import com.paperpilot.api.mapper.GitRepositoryMapper;
import com.paperpilot.api.mapper.PaperMapper;
import com.paperpilot.api.mapper.ProjectMapper;
import com.paperpilot.api.mapper.StageExecutionMapper;
import com.paperpilot.api.mq.StageMessageConsumer;
import com.paperpilot.api.mq.StageMessageProducer;
import com.paperpilot.api.progress.TaskProgressService;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Primary;
import org.springframework.data.redis.connection.RedisConnectionFactory;
import org.springframework.data.redis.connection.RedisStandaloneConfiguration;
import org.springframework.data.redis.connection.lettuce.LettuceConnectionFactory;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.transaction.PlatformTransactionManager;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.containers.MySQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import javax.sql.DataSource;
import java.time.Duration;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doAnswer;

/**
 * Redis 进度集成：真实 Redis + 完整编排链路，终态后 Redis 进度为 100/SUCCEEDED，
 * 且 MySQL 终态优先于任何 Redis 冲突值.
 */
@SpringBootTest
@Testcontainers
class RedisIntegrationTest {

    @Container
    static final MySQLContainer<?> MYSQL = new MySQLContainer<>("mysql:8.0");
    @Container
    static final GenericContainer<?> REDIS = new GenericContainer<>("redis:7-alpine")
            .withExposedPorts(6379);

    static FakeWorkerServer FAKE_WORKER;

    @MockBean
    StageMessageProducer producer;

    @Autowired
    StageMessageConsumer consumer;
    @Autowired
    TaskProgressService progressService;
    @Autowired
    AnalysisTaskMapper taskMapper;
    @Autowired
    StageExecutionMapper stageExecutionMapper;
    @Autowired
    ProjectMapper projectMapper;
    @Autowired
    GitRepositoryMapper repositoryMapper;
    @Autowired
    PaperMapper paperMapper;

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
        doAnswer(inv -> {
            StageTaskMessage msg = inv.getArgument(0);
            consumer.onMessage(StageTaskMessage.toJson(msg));
            return null;
        }).when(producer).send(any(StageTaskMessage.class));
    }

    @Test
    void fullFlowWritesTerminalProgressAndMySqlWins() throws Exception {
        long taskId = insertRunningTaskWithStages();

        StageExecution parse = stageBy(taskId, TaskStage.PARSE_PAPER);
        consumer.onMessage(StageTaskMessage.toJson(
                StageTaskMessage.create(taskId, parse.getId(), TaskStage.PARSE_PAPER, 1)));

        // 全链路推进到 SUCCEEDED
        awaitUntilTrue(() -> taskMapper.selectById(taskId).getStatus() == TaskStatus.SUCCEEDED);

        // Redis 进度为终态 100/SUCCEEDED
        TaskProgressSnapshot snap = progressService.read(taskId);
        assertThat(snap).isNotNull();
        assertThat(snap.status()).isEqualTo("SUCCEEDED");
        assertThat(snap.progress()).isEqualTo(100);

        // 查询视图：MySQL 终态优先（即使 Redis 被写入冲突值也不影响）
        progressService.update(taskId, TaskStatus.RUNNING, TaskStage.INDEX_CODE, 55, "stale");
        TaskProgressView view = progressService.getView(taskId);
        assertThat(view.status()).isEqualTo("SUCCEEDED");
        assertThat(view.progress()).isEqualTo(100);
        assertThat(view.message()).isEqualTo("任务完成");
    }

    private long insertRunningTaskWithStages() {
        Project project = new Project();
        project.setName("p-" + UUID.randomUUID());
        projectMapper.insert(project);

        GitRepository repo = new GitRepository();
        repo.setProjectId(project.getId());
        repo.setGithubUrl("https://github.com/paperpilot/patchtst");
        repositoryMapper.insert(repo);

        Paper paper = new Paper();
        paper.setProjectId(project.getId());
        paper.setTitle("PatchTST");
        paper.setPdfUrl("http://example.com/p.pdf");
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

    private StageExecution stageBy(long taskId, TaskStage stage) {
        return stageExecutionMapper.selectList(new com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper<StageExecution>()
                .eq(StageExecution::getTaskId, taskId)
                .eq(StageExecution::getStage, stage)
                .eq(StageExecution::getAttempt, 1)).get(0);
    }

    private static void awaitUntilTrue(java.util.function.BooleanSupplier condition) {
        long deadline = System.nanoTime() + Duration.ofSeconds(15).toNanos();
        while (System.nanoTime() < deadline) {
            if (condition.getAsBoolean()) {
                return;
            }
            try {
                Thread.sleep(50);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                throw new AssertionError(e);
            }
        }
        throw new AssertionError("等待超时");
    }

    @TestConfiguration
    static class DbConfig {

        @Bean
        @Primary
        DataSource dataSource() {
            return TestSupport.dataSource(MYSQL);
        }

        @Bean
        PlatformTransactionManager transactionManager(DataSource dataSource) {
            return new DataSourceTransactionManager(dataSource);
        }

        @Bean
        @Primary
        com.paperpilot.api.worker.WorkerProperties workerProperties() {
            return new com.paperpilot.api.worker.WorkerProperties(FAKE_WORKER.baseUrl(),
                    Duration.ofSeconds(3), Duration.ofSeconds(10), java.util.Map.of());
        }

        @Bean
        @Primary
        RedisConnectionFactory redisConnectionFactory() {
            LettuceConnectionFactory factory = new LettuceConnectionFactory(
                    new RedisStandaloneConfiguration(REDIS.getHost(), REDIS.getMappedPort(6379)));
            factory.afterPropertiesSet();
            return factory;
        }

        @Bean
        StringRedisTemplate stringRedisTemplate(RedisConnectionFactory factory) {
            StringRedisTemplate template = new StringRedisTemplate(factory);
            template.afterPropertiesSet();
            return template;
        }

        @Bean
        @Primary
        org.springframework.data.redis.core.RedisTemplate<Object, Object> redisTemplate(
                RedisConnectionFactory factory) {
            org.springframework.data.redis.core.RedisTemplate<Object, Object> template =
                    new org.springframework.data.redis.core.RedisTemplate<>();
            template.setConnectionFactory(factory);
            template.afterPropertiesSet();
            return template;
        }
    }
}

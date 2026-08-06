package com.paperpilot.api.service;

import com.paperpilot.api.TestSupport;
import com.paperpilot.api.domain.entity.AnalysisTask;
import com.paperpilot.api.domain.entity.Paper;
import com.paperpilot.api.domain.entity.Project;
import com.paperpilot.api.domain.enums.TaskStage;
import com.paperpilot.api.domain.enums.TaskStatus;
import com.paperpilot.api.mapper.AnalysisTaskMapper;
import com.paperpilot.api.mapper.PaperMapper;
import com.paperpilot.api.mapper.ProjectMapper;
import com.paperpilot.api.progress.TaskEventProperties;
import com.paperpilot.api.progress.TaskProgressProperties;
import com.paperpilot.api.progress.TaskProgressService;
import org.apache.ibatis.session.SqlSession;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.support.DefaultListableBeanFactory;
import org.springframework.data.redis.connection.RedisConnectionFactory;
import org.springframework.data.redis.connection.RedisStandaloneConfiguration;
import org.springframework.data.redis.connection.lettuce.LettuceConnectionFactory;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.containers.MySQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import javax.sql.DataSource;
import java.time.Duration;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.function.Predicate;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Redis Pub/Sub + SSE：首条 snapshot、跨实例收事件、heartbeat 不污染状态、断线清理无泄漏、重连快照恢复.
 */
@Testcontainers
class TaskEventSseTest {

    @Container
    static final MySQLContainer<?> MYSQL = new MySQLContainer<>("mysql:8.0");
    @Container
    static final GenericContainer<?> REDIS = new GenericContainer<>("redis:7-alpine")
            .withExposedPorts(6379);

    static TaskEventService serviceA;
    static TaskEventService serviceB;
    static TaskProgressService progressService;
    static AnalysisTaskMapper taskMapper;
    static SqlSession session;

    static class RecordingEmitter extends SseEmitter {
        final List<Object> sent = new CopyOnWriteArrayList<>();
        volatile boolean closed = false;

        RecordingEmitter() {
            super(Duration.ofHours(2).toMillis());
        }

        @Override
        public void send(Object object) {
            if (closed) {
                throw new IllegalStateException("closed"); // 模拟真实 emitter 断开后 send 失败
            }
            sent.add(object);
        }

        @Override
        public void complete() {
            closed = true;
            super.complete();
        }
    }

    @BeforeAll
    static void setUp() throws Exception {
        LettuceConnectionFactory factory = new LettuceConnectionFactory(
                new RedisStandaloneConfiguration(REDIS.getHost(), REDIS.getMappedPort(6379)));
        factory.afterPropertiesSet();
        StringRedisTemplate template = new StringRedisTemplate(factory);
        template.afterPropertiesSet();

        DefaultListableBeanFactory beanFactory = new DefaultListableBeanFactory();
        beanFactory.registerSingleton("stringRedisTemplate", template);
        beanFactory.registerSingleton("redisConnectionFactory", factory);
        ObjectProvider<StringRedisTemplate> redisProvider =
                beanFactory.getBeanProvider(StringRedisTemplate.class);
        ObjectProvider<RedisConnectionFactory> factoryProvider =
                beanFactory.getBeanProvider(RedisConnectionFactory.class);

        DataSource ds = TestSupport.dataSource(MYSQL);
        Flyway.configure().dataSource(ds).load().migrate();
        session = TestSupport.buildFactory(ds).openSession(true);
        taskMapper = session.getMapper(AnalysisTaskMapper.class);

        progressService = new TaskProgressService(
                redisProvider, new TaskProgressProperties(), taskMapper);
        TaskEventProperties props = new TaskEventProperties(
                "paperpilot:task", Duration.ofHours(2), Duration.ofMillis(200), 100, 1000);

        serviceA = new TaskEventService(redisProvider, factoryProvider, taskMapper, progressService, props);
        serviceA.start();
        serviceB = new TaskEventService(redisProvider, factoryProvider, taskMapper, progressService, props);
        serviceB.start();
    }

    @AfterAll
    static void tearDown() {
        serviceA.stop();
        serviceB.stop();
        if (session != null) {
            session.close();
        }
    }

    @Test
    void firstEventIsSnapshotAndCrossInstanceReceives() throws Exception {
        long taskId = insertTask(TaskStatus.RUNNING);
        RecordingEmitter emitter = new RecordingEmitter();
        serviceA.subscribe(taskId, emitter);
        awaitUntil(emitter.sent, e -> e instanceof TaskEvent t && TaskEventType.SNAPSHOT.equals(t.type()));

        // 实例 B 发布 → 实例 A 收到（跨实例 Pub/Sub）
        serviceB.publish(taskId, TaskEventType.STAGE_STARTED,
                new TaskEventPayload("RUNNING", "PARSE_PAPER", 5, "开始"));
        awaitUntil(emitter.sent,
                e -> e instanceof TaskEvent t && TaskEventType.STAGE_STARTED.equals(t.type()));

        TaskEvent first = (TaskEvent) emitter.sent.get(0);
        assertThat(first.type()).isEqualTo(TaskEventType.SNAPSHOT); // 首条业务事件为 snapshot
        assertThat(((TaskEventPayload) first.payload()).status()).isEqualTo("RUNNING");
    }

    @Test
    void heartbeatVisibleAndDoesNotPolluteTaskState() throws Exception {
        long taskId = insertTask(TaskStatus.RUNNING);
        RecordingEmitter emitter = new RecordingEmitter();
        serviceA.subscribe(taskId, emitter);

        awaitUntil(emitter.sent, e -> e instanceof TaskEvent t && TaskEventType.HEARTBEAT.equals(t.type()));
        assertThat(taskMapper.selectById(taskId).getStatus()).isEqualTo(TaskStatus.RUNNING);
    }

    @Test
    void disconnectCleansUpWithoutFailingTask() throws Exception {
        long taskId = insertTask(TaskStatus.RUNNING);
        RecordingEmitter emitter = new RecordingEmitter();
        serviceA.subscribe(taskId, emitter);
        awaitUntil(emitter.sent, e -> e instanceof TaskEvent t && TaskEventType.SNAPSHOT.equals(t.type()));

        emitter.complete(); // 客户端断开
        int before = emitter.sent.size();
        Thread.sleep(400); // 等清理 + 心跳周期
        serviceB.publish(taskId, TaskEventType.STAGE_PROGRESS,
                new TaskEventPayload("RUNNING", "PARSE_PAPER", 10, "x"));
        Thread.sleep(300);

        assertThat(emitter.sent.size()).isEqualTo(before); // 断线 emitter 不再收到事件
        assertThat(taskMapper.selectById(taskId).getStatus()).isEqualTo(TaskStatus.RUNNING);
    }

    @Test
    void reconnectGetsFreshSnapshot() throws Exception {
        long taskId = insertTask(TaskStatus.RUNNING);
        // 写入 Redis 进度键（快照从 MySQL+Redis 恢复，而非依赖 Pub/Sub 历史）
        progressService.update(taskId, TaskStatus.RUNNING, TaskStage.INDEX_CODE, 15, "p");

        RecordingEmitter reconnected = new RecordingEmitter();
        serviceA.subscribe(taskId, reconnected);

        awaitUntil(reconnected.sent, e -> e instanceof TaskEvent t && TaskEventType.SNAPSHOT.equals(t.type()));
        TaskEvent first = (TaskEvent) reconnected.sent.get(0);
        assertThat(first.type()).isEqualTo(TaskEventType.SNAPSHOT);
        assertThat(((TaskEventPayload) first.payload()).progress()).isEqualTo(15);
    }

    // ── helpers ──────────────────────────────────────────────────────────

    private long insertTask(TaskStatus status) {
        ProjectMapper projectMapper = session.getMapper(ProjectMapper.class);
        PaperMapper paperMapper = session.getMapper(PaperMapper.class);
        Project project = new Project();
        project.setName("p-" + UUID.randomUUID());
        projectMapper.insert(project);
        Paper paper = new Paper();
        paper.setProjectId(project.getId());
        paper.setTitle("t");
        paper.setPdfUrl("http://example.com/p.pdf");
        paperMapper.insert(paper);
        AnalysisTask task = new AnalysisTask();
        task.setProjectId(project.getId());
        task.setPaperId(paper.getId());
        task.setStatus(status);
        task.setRequestKey("req-" + UUID.randomUUID());
        taskMapper.insert(task);
        return task.getId();
    }

    private static void awaitUntil(List<Object> sent, Predicate<Object> predicate) throws Exception {
        long deadline = System.nanoTime() + Duration.ofSeconds(10).toNanos();
        while (System.nanoTime() < deadline) {
            if (sent.stream().anyMatch(predicate)) {
                return;
            }
            Thread.sleep(50);
        }
        throw new AssertionError("等待事件超时: " + sent);
    }
}

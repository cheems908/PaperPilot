package com.paperpilot.api;

import com.paperpilot.api.domain.entity.AnalysisTask;
import com.paperpilot.api.domain.entity.Paper;
import com.paperpilot.api.domain.entity.Project;
import com.paperpilot.api.domain.enums.TaskStage;
import com.paperpilot.api.domain.enums.TaskStatus;
import com.paperpilot.api.dto.progress.TaskProgressSnapshot;
import com.paperpilot.api.dto.progress.TaskProgressView;
import com.paperpilot.api.mapper.AnalysisTaskMapper;
import com.paperpilot.api.mapper.PaperMapper;
import com.paperpilot.api.mapper.ProjectMapper;
import com.paperpilot.api.progress.TaskProgressProperties;
import com.paperpilot.api.progress.TaskProgressService;
import org.apache.ibatis.session.SqlSession;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.support.DefaultListableBeanFactory;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.data.redis.connection.RedisStandaloneConfiguration;
import org.springframework.data.redis.connection.lettuce.LettuceConnectionFactory;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.containers.MySQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import javax.sql.DataSource;
import java.util.UUID;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 任务进度缓存：key/TTL/序列化、进度不倒退、MySQL 终态优先、无大对象.
 */
@Testcontainers
class TaskProgressPersistenceTest {

    @Container
    static final MySQLContainer<?> MYSQL = new MySQLContainer<>("mysql:8.0");
    @Container
    static final GenericContainer<?> REDIS = new GenericContainer<>("redis:7-alpine")
            .withExposedPorts(6379);

    static TaskProgressService service;
    static StringRedisTemplate template;
    static SqlSession session;
    static AnalysisTaskMapper taskMapper;
    static ProjectMapper projectMapper;
    static PaperMapper paperMapper;

    @BeforeAll
    static void setUp() throws Exception {
        LettuceConnectionFactory factory = new LettuceConnectionFactory(
                new RedisStandaloneConfiguration(REDIS.getHost(), REDIS.getMappedPort(6379)));
        factory.afterPropertiesSet();
        template = new StringRedisTemplate(factory);
        template.afterPropertiesSet();

        DefaultListableBeanFactory beanFactory = new DefaultListableBeanFactory();
        beanFactory.registerSingleton("stringRedisTemplate", template);
        ObjectProvider<StringRedisTemplate> provider =
                beanFactory.getBeanProvider(StringRedisTemplate.class);

        DataSource ds = TestSupport.dataSource(MYSQL);
        Flyway.configure().dataSource(ds).load().migrate();
        session = TestSupport.buildFactory(ds).openSession(true);
        taskMapper = session.getMapper(AnalysisTaskMapper.class);
        projectMapper = session.getMapper(ProjectMapper.class);
        paperMapper = session.getMapper(PaperMapper.class);
        service = new TaskProgressService(provider, new TaskProgressProperties(), taskMapper);
    }

    @AfterAll
    static void tearDown() {
        if (session != null) {
            session.close();
        }
    }

    @Test
    void writeReadRoundTripsWithKeyAndTtl() {
        service.update(7L, TaskStatus.RUNNING, TaskStage.INDEX_CODE, 55, "Indexed 320 symbols");

        TaskProgressSnapshot snap = service.read(7L);
        assertThat(snap).isNotNull();
        assertThat(snap.schemaVersion()).isEqualTo(TaskProgressSnapshot.SCHEMA_VERSION);
        assertThat(snap.taskId()).isEqualTo(7L);
        assertThat(snap.status()).isEqualTo("RUNNING");
        assertThat(snap.stage()).isEqualTo("INDEX_CODE");
        assertThat(snap.progress()).isEqualTo(55);
        assertThat(snap.message()).isEqualTo("Indexed 320 symbols");
        assertThat(snap.updatedAt()).isNotNull();
        // TTL 约 24h
        Long ttlSeconds = template.getExpire("paperpilot:task:7:progress", TimeUnit.SECONDS);
        assertThat(ttlSeconds).isNotNull().isGreaterThan(0).isLessThanOrEqualTo(24L * 3600);
    }

    @Test
    void progressDoesNotRegress() {
        service.update(8L, TaskStatus.RUNNING, TaskStage.INDEX_CODE, 50, "half");
        service.update(8L, TaskStatus.RUNNING, TaskStage.INDEX_CODE, 30, "regress"); // 应被拒绝
        TaskProgressSnapshot snap = service.read(8L);
        assertThat(snap.progress()).isEqualTo(50);
        assertThat(snap.message()).isEqualTo("half");
    }

    @Test
    void terminalWriteReaches100() {
        service.update(9L, TaskStatus.RUNNING, TaskStage.MAP_CONCEPTS, 90, "mapping");
        service.update(9L, TaskStatus.SUCCEEDED, null, 100, "任务完成");
        TaskProgressSnapshot snap = service.read(9L);
        assertThat(snap.progress()).isEqualTo(100);
        assertThat(snap.status()).isEqualTo("SUCCEEDED");
    }

    @Test
    void mySqlTerminalStatusWinsOverRedis() {
        long taskId = insertTask(TaskStatus.SUCCEEDED);
        // Redis 里是过期/错乱的 RUNNING 55
        service.update(taskId, TaskStatus.RUNNING, TaskStage.INDEX_CODE, 55, "stale");

        TaskProgressView view = service.getView(taskId);
        assertThat(view.status()).isEqualTo("SUCCEEDED");
        assertThat(view.progress()).isEqualTo(100); // 以 MySQL 终态为准
        assertThat(view.message()).isEqualTo("任务完成");
    }

    @Test
    void mySqlRunningStatusSupplementedByRedis() {
        long taskId = insertTask(TaskStatus.RUNNING);
        service.update(taskId, TaskStatus.RUNNING, TaskStage.INDEX_CODE, 55, "Indexed 320 symbols");

        TaskProgressView view = service.getView(taskId);
        assertThat(view.status()).isEqualTo("RUNNING"); // MySQL 状态
        assertThat(view.progress()).isEqualTo(55);      // Redis 补充
        assertThat(view.stage()).isEqualTo("INDEX_CODE");
    }

    @Test
    void snapshotDoesNotCarryLargeObjects() {
        String json = "{\"schemaVersion\":1,\"taskId\":10,\"status\":\"RUNNING\",\"stage\":\"PARSE_PAPER\","
                + "\"progress\":10,\"message\":\"short\",\"updatedAt\":\"2026-08-06T00:00:00Z\"}";
        assertThat(json.length()).isLessThan(200); // 只有小元数据，无全文/源码
    }

    private long insertTask(TaskStatus status) {
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
}

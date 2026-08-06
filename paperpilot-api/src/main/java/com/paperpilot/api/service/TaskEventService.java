package com.paperpilot.api.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.paperpilot.api.common.ApiException;
import com.paperpilot.api.common.ErrorCode;
import com.paperpilot.api.domain.entity.AnalysisTask;
import com.paperpilot.api.dto.progress.TaskProgressView;
import com.paperpilot.api.dto.snapshot.StageSnapshotContract;
import com.paperpilot.api.mapper.AnalysisTaskMapper;
import com.paperpilot.api.progress.TaskEventProperties;
import com.paperpilot.api.progress.TaskProgressService;
import jakarta.annotation.PostConstruct;
import jakarta.annotation.PreDestroy;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.data.redis.connection.Message;
import org.springframework.data.redis.connection.MessageListener;
import org.springframework.data.redis.connection.RedisConnectionFactory;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.listener.PatternTopic;
import org.springframework.data.redis.listener.RedisMessageListenerContainer;
import org.springframework.stereotype.Service;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 任务事件广播（Redis Pub/Sub + SSE）.
 *
 * <p>事件经 Redis 频道 {@code paperpilot:task:{taskId}:events} 跨实例广播；
 * 每个实例用全局 PatternTopic 订阅并把事件路由到本实例的 SseEmitter。
 * 连接建立先查 MySQL 正式状态 + Redis 进度构造 {@code task-snapshot} 立即推送，
 * 再接收后续事件；重连靠 snapshot 恢复（Pub/Sub 不持久化，不假设不丢事件）。
 * 每 15~30s 心跳；断线只移除 emitter，不影响任务状态。
 * 无 Redis 时退化为本实例内存路由。
 */
@Service
public class TaskEventService {

    private static final Logger log = LoggerFactory.getLogger(TaskEventService.class);
    private static final Pattern CHANNEL_ID = Pattern.compile(":([0-9]+):events$");

    private static final ExecutorService EVENT_PUSH = Executors.newSingleThreadExecutor(r -> {
        Thread t = new Thread(r, "task-event-push");
        t.setDaemon(true);
        return t;
    });
    private static final ScheduledExecutorService HEARTBEAT = Executors.newSingleThreadScheduledExecutor(r -> {
        Thread t = new Thread(r, "task-event-heartbeat");
        t.setDaemon(true);
        return t;
    });

    private final ObjectProvider<StringRedisTemplate> redisProvider;
    private final ObjectProvider<RedisConnectionFactory> factoryProvider;
    private final AnalysisTaskMapper taskMapper;
    private final TaskProgressService progressService;
    private final TaskEventProperties properties;

    private final Map<Long, CopyOnWriteArrayList<SseEmitter>> subscribers = new ConcurrentHashMap<>();
    private final Map<Long, AtomicInteger> perTaskConnections = new ConcurrentHashMap<>();
    private final AtomicInteger globalConnections = new AtomicInteger();
    private final AtomicLong sequence = new AtomicLong();

    private volatile RedisMessageListenerContainer container;

    public TaskEventService(ObjectProvider<StringRedisTemplate> redisProvider,
                            ObjectProvider<RedisConnectionFactory> factoryProvider,
                            AnalysisTaskMapper taskMapper,
                            TaskProgressService progressService,
                            TaskEventProperties properties) {
        this.redisProvider = redisProvider;
        this.factoryProvider = factoryProvider;
        this.taskMapper = taskMapper;
        this.progressService = progressService;
        this.properties = properties;
    }

    @PostConstruct
    public void start() {
        RedisConnectionFactory factory = factoryProvider.getIfAvailable();
        if (factory == null) {
            log.debug("Redis 未配置，任务事件走本实例内存路由");
            return;
        }
        container = new RedisMessageListenerContainer();
        container.setConnectionFactory(factory);
        container.addMessageListener(new MessageListener() {
            @Override
            public void onMessage(Message message, byte[] pattern) {
                handleRedisMessage(message);
            }
        }, new PatternTopic(properties.channelPrefix() + ":*:events"));
        container.afterPropertiesSet();
        container.start();
        // 全局心跳：周期向本实例全部活跃 emitter 发 heartbeat，顺带清理失效连接
        HEARTBEAT.scheduleAtFixedRate(this::broadcastHeartbeat,
                properties.heartbeatInterval().toMillis(), properties.heartbeatInterval().toMillis(),
                TimeUnit.MILLISECONDS);
    }

    @PreDestroy
    public void stop() {
        if (container != null) {
            container.stop();
        }
    }

    /**
     * 订阅任务事件流：先查 MySQL + Redis 构造并推送 {@code task-snapshot}，再收后续事件。
     * 连接数超限抛 {@code CONFLICT}。
     */
    public SseEmitter subscribe(Long taskId) {
        return subscribe(taskId, new SseEmitter(properties.emitterTimeout().toMillis()));
    }

    /** 测试用重载：注入自定义 emitter（如 recording emitter）。 */
    SseEmitter subscribe(Long taskId, SseEmitter emitter) {
        AnalysisTask task = taskMapper.selectById(taskId);
        if (task == null) {
            throw new ApiException(ErrorCode.NOT_FOUND, "任务不存在");
        }
        AtomicInteger perTask = perTaskConnections.computeIfAbsent(taskId, k -> new AtomicInteger());
        if (perTask.get() >= properties.maxConnectionsPerTask()
                || globalConnections.get() >= properties.maxGlobalConnections()) {
            throw new ApiException(ErrorCode.CONFLICT, "连接数超限");
        }
        perTask.incrementAndGet();
        globalConnections.incrementAndGet();

        subscribers.computeIfAbsent(taskId, k -> new CopyOnWriteArrayList<>()).add(emitter);
        emitter.onCompletion(() -> remove(taskId, emitter));
        emitter.onTimeout(() -> remove(taskId, emitter));
        emitter.onError(e -> remove(taskId, emitter));

        TaskEvent snapshot = TaskEvent.of(sequence.incrementAndGet(), taskId,
                TaskEventType.SNAPSHOT, snapshotPayload(task));
        EVENT_PUSH.execute(() -> sendIgnoringFailure(emitter, snapshot));
        return emitter;
    }

    /** 发布事件：优先经 Redis Pub/Sub 广播（本实例订阅者经监听回环接收），无 Redis 时内存直发。 */
    public void publish(Long taskId, String type, TaskEventPayload payload) {
        TaskEvent event = TaskEvent.of(sequence.incrementAndGet(), taskId, type, payload);
        StringRedisTemplate redis = redisProvider.getIfAvailable();
        if (redis != null) {
            try {
                redis.convertAndSend(properties.channelFor(taskId), toJson(event));
            } catch (Exception e) {
                log.warn("Redis 事件发布失败 taskId={} type={}: {}", taskId, type, e.getMessage());
                route(taskId, event); // 兜底：本实例内存
            }
        } else {
            route(taskId, event);
        }
    }

    // ── 内部 ──────────────────────────────────────────────────────────────

    private void handleRedisMessage(Message message) {
        String channel = new String(message.getChannel(), StandardCharsets.UTF_8);
        Matcher matcher = CHANNEL_ID.matcher(channel);
        if (!matcher.find()) {
            return;
        }
        Long taskId = Long.parseLong(matcher.group(1));
        TaskEvent event = fromJson(new String(message.getBody(), StandardCharsets.UTF_8));
        route(taskId, event);
    }

    private void route(Long taskId, TaskEvent event) {
        List<SseEmitter> list = subscribers.get(taskId);
        if (list == null || list.isEmpty()) {
            return;
        }
        for (SseEmitter emitter : list) {
            if (!send(emitter, event)) {
                remove(taskId, emitter);
            }
        }
    }

    private void broadcastHeartbeat() {
        TaskEvent beat = TaskEvent.of(sequence.incrementAndGet(), null, TaskEventType.HEARTBEAT,
                new TaskEventPayload(null, null, null, "heartbeat"));
        subscribers.forEach((taskId, list) -> {
            for (SseEmitter emitter : list) {
                if (!send(emitter, beat)) {
                    remove(taskId, emitter);
                }
            }
        });
    }

    private TaskEventPayload snapshotPayload(AnalysisTask task) {
        TaskProgressView view = progressService.getView(task.getId());
        return new TaskEventPayload(task.getStatus().name(), view.stage(), view.progress(), view.message());
    }

    private void remove(Long taskId, SseEmitter emitter) {
        List<SseEmitter> list = subscribers.get(taskId);
        if (list != null && list.remove(emitter)) {
            perTaskConnections.computeIfAbsent(taskId, k -> new AtomicInteger()).decrementAndGet();
            globalConnections.decrementAndGet();
            if (list.isEmpty()) {
                subscribers.remove(taskId);
                perTaskConnections.remove(taskId);
            }
        }
    }

    private boolean send(SseEmitter emitter, TaskEvent event) {
        try {
            emitter.send(event);
            return true;
        } catch (IOException | IllegalStateException e) {
            return false;
        }
    }

    private void sendIgnoringFailure(SseEmitter emitter, TaskEvent event) {
        try {
            emitter.send(event);
        } catch (IOException | IllegalStateException ignored) {
            // 连接尚未初始化或已断开：忽略
        }
    }

    private static final ObjectMapper MAPPER = StageSnapshotContract.MAPPER;

    private String toJson(TaskEvent event) {
        try {
            return MAPPER.writeValueAsString(event);
        } catch (Exception e) {
            throw new IllegalStateException("事件序列化失败", e);
        }
    }

    private TaskEvent fromJson(String json) {
        try {
            return MAPPER.readValue(json, TaskEvent.class);
        } catch (Exception e) {
            log.warn("事件反序列化失败: {}", e.getMessage());
            return null;
        }
    }
}

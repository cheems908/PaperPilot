package com.paperpilot.api.service;

import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import java.io.IOException;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

/**
 * 任务事件推送（MVP：内存 SseEmitter 注册表）.
 *
 * <p>订阅端按 {@code taskId} 注册 {@link SseEmitter}，任务生命周期事件
 * （创建/状态变更/终态）通过 {@link #publish} fan-out 推送。
 * Redis Pub/Sub 跨实例广播留待后续阶段。
 *
 * <p>初始快照事件由独立线程异步推送：Spring 在控制器方法返回后才初始化
 * emitter 的响应处理器，同步 send 会抛 {@code IllegalStateException}。
 */
@Service
@RequiredArgsConstructor
public class TaskEventService {

    private static final long SSE_TIMEOUT = TimeUnit.HOURS.toMillis(2);

    private static final ExecutorService EVENT_PUSH = Executors.newSingleThreadExecutor(r -> {
        Thread t = new Thread(r, "task-event-push");
        t.setDaemon(true);
        return t;
    });

    private final Map<Long, List<SseEmitter>> subscribers = new ConcurrentHashMap<>();

    /**
     * 订阅任务事件流；{@code initial} 非空时异步推送初始快照（迟连者可立即拿到当前状态）.
     */
    public SseEmitter subscribe(Long taskId, TaskEvent initial) {
        SseEmitter emitter = new SseEmitter(SSE_TIMEOUT);
        subscribers.computeIfAbsent(taskId, k -> new CopyOnWriteArrayList<>()).add(emitter);
        emitter.onCompletion(() -> remove(taskId, emitter));
        emitter.onTimeout(() -> remove(taskId, emitter));
        emitter.onError(e -> remove(taskId, emitter));
        if (initial != null) {
            EVENT_PUSH.execute(() -> sendIgnoringFailure(emitter, initial));
        }
        return emitter;
    }

    /** 向某任务的全部订阅者推送事件；失败的订阅者移除。 */
    public void publish(Long taskId, TaskEvent event) {
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

    private boolean send(SseEmitter emitter, TaskEvent event) {
        try {
            emitter.send(SseEmitter.event().name("task").data(event));
            return true;
        } catch (IOException e) {
            return false;
        }
    }

    private void sendIgnoringFailure(SseEmitter emitter, TaskEvent event) {
        try {
            emitter.send(SseEmitter.event().name("task").data(event));
        } catch (IOException | IllegalStateException ignored) {
            // 连接尚未初始化或已断开：忽略，后续事件仍可推送
        }
    }

    private void remove(Long taskId, SseEmitter emitter) {
        List<SseEmitter> list = subscribers.get(taskId);
        if (list != null) {
            list.remove(emitter);
            if (list.isEmpty()) {
                subscribers.remove(taskId);
            }
        }
    }
}

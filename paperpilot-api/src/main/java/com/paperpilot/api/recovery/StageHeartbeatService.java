package com.paperpilot.api.recovery;

import com.baomidou.mybatisplus.core.conditions.update.LambdaUpdateWrapper;
import com.paperpilot.api.domain.entity.StageExecution;
import com.paperpilot.api.domain.enums.StageExecutionStatus;
import com.paperpilot.api.mapper.StageExecutionMapper;
import jakarta.annotation.PreDestroy;
import org.springframework.stereotype.Component;

import java.time.LocalDateTime;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;

/** Worker 调用期间定期续写 MySQL heartbeat；不依赖 Redis。 */
@Component
public class StageHeartbeatService {

    private final StageExecutionMapper stageMapper;
    private final RecoveryProperties properties;
    private final ScheduledExecutorService executor = Executors.newScheduledThreadPool(1, runnable -> {
        Thread thread = new Thread(runnable, "stage-execution-heartbeat");
        thread.setDaemon(true);
        return thread;
    });

    public StageHeartbeatService(StageExecutionMapper stageMapper, RecoveryProperties properties) {
        this.stageMapper = stageMapper;
        this.properties = properties;
    }

    public HeartbeatLease begin(Long stageExecutionId) {
        pulse(stageExecutionId);
        long interval = properties.heartbeatInterval().toMillis();
        ScheduledFuture<?> future = executor.scheduleWithFixedDelay(
                () -> pulse(stageExecutionId), interval, interval, TimeUnit.MILLISECONDS);
        return () -> future.cancel(false);
    }

    private void pulse(Long stageExecutionId) {
        stageMapper.update(null, new LambdaUpdateWrapper<StageExecution>()
                .eq(StageExecution::getId, stageExecutionId)
                .eq(StageExecution::getStatus, StageExecutionStatus.RUNNING)
                .set(StageExecution::getHeartbeatAt, LocalDateTime.now()));
    }

    @PreDestroy
    void shutdown() {
        executor.shutdownNow();
    }

    @FunctionalInterface
    public interface HeartbeatLease extends AutoCloseable {
        @Override
        void close();
    }
}

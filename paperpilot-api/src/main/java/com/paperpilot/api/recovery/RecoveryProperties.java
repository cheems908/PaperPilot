package com.paperpilot.api.recovery;

import org.springframework.boot.context.properties.ConfigurationProperties;

import java.time.Duration;

/** 基于 MySQL 的任务恢复与执行心跳配置。 */
@ConfigurationProperties(prefix = "paperpilot.recovery")
public record RecoveryProperties(Duration scanInterval,
                                 Duration queuedTimeout,
                                 Duration runningTimeout,
                                 Duration heartbeatInterval,
                                 int batchSize) {

    public RecoveryProperties {
        scanInterval = scanInterval == null ? Duration.ofSeconds(15) : scanInterval;
        queuedTimeout = queuedTimeout == null ? Duration.ofSeconds(30) : queuedTimeout;
        runningTimeout = runningTimeout == null ? Duration.ofMinutes(2) : runningTimeout;
        heartbeatInterval = heartbeatInterval == null ? Duration.ofSeconds(20) : heartbeatInterval;
        batchSize = batchSize <= 0 ? 100 : batchSize;
        if (heartbeatInterval.compareTo(runningTimeout) >= 0) {
            throw new IllegalArgumentException("paperpilot.recovery.heartbeat-interval 必须小于 running-timeout");
        }
    }
}

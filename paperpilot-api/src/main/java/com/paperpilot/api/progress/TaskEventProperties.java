package com.paperpilot.api.progress;

import org.springframework.boot.context.properties.ConfigurationProperties;

import java.time.Duration;

/**
 * 任务事件配置（{@code paperpilot.event.*}）：频道前缀、emitter 超时、心跳间隔、连接数上限.
 */
@ConfigurationProperties(prefix = "paperpilot.event")
public record TaskEventProperties(
        String channelPrefix,
        Duration emitterTimeout,
        Duration heartbeatInterval,
        int maxConnectionsPerTask,
        int maxGlobalConnections) {

    public TaskEventProperties() {
        this(null, null, null, 0, 0);
    }

    public TaskEventProperties {
        channelPrefix = (channelPrefix == null || channelPrefix.isBlank()) ? "paperpilot:task" : channelPrefix;
        emitterTimeout = (emitterTimeout == null) ? Duration.ofHours(2) : emitterTimeout;
        heartbeatInterval = (heartbeatInterval == null) ? Duration.ofSeconds(20) : heartbeatInterval;
        maxConnectionsPerTask = maxConnectionsPerTask <= 0 ? 100 : maxConnectionsPerTask;
        maxGlobalConnections = maxGlobalConnections <= 0 ? 1000 : maxGlobalConnections;
    }

    /** 事件频道：{@code paperpilot:task:{taskId}:events}。 */
    public String channelFor(Long taskId) {
        return channelPrefix + ":" + taskId + ":events";
    }
}

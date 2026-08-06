package com.paperpilot.api.progress;

import org.springframework.boot.context.properties.ConfigurationProperties;

import java.time.Duration;

/**
 * 进度缓存配置（{@code paperpilot.progress.*}）.
 */
@ConfigurationProperties(prefix = "paperpilot.progress")
public record TaskProgressProperties(String keyPrefix, Duration ttl) {

    public TaskProgressProperties() {
        this(null, null);
    }

    public TaskProgressProperties {
        keyPrefix = (keyPrefix == null || keyPrefix.isBlank()) ? "paperpilot:task" : keyPrefix;
        ttl = (ttl == null) ? Duration.ofHours(24) : ttl;
    }

    /** 进度 key：{@code paperpilot:task:{taskId}:progress}。 */
    public String keyFor(Long taskId) {
        return keyPrefix + ":" + taskId + ":progress";
    }
}

package com.paperpilot.api.retry;

import org.springframework.boot.context.properties.ConfigurationProperties;

import java.time.Duration;

/** 自动重试配置（{@code paperpilot.retry.*}）。 */
@ConfigurationProperties(prefix = "paperpilot.retry")
public record RetryProperties(int maxAttempts, int scanBatchSize, Duration scanInterval) {

    public RetryProperties() {
        this(0, 0, null);
    }

    public RetryProperties {
        maxAttempts = maxAttempts <= 0 ? 4 : maxAttempts;
        scanBatchSize = scanBatchSize <= 0 ? 100 : scanBatchSize;
        scanInterval = scanInterval == null ? Duration.ofSeconds(5) : scanInterval;
        if (maxAttempts > 4) {
            throw new IllegalArgumentException("paperpilot.retry.max-attempts 不能超过三档退避支持的 4 次执行");
        }
    }
}

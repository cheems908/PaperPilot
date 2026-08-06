package com.paperpilot.api.retry;

import org.springframework.stereotype.Component;

import java.time.Duration;
import java.util.List;
import java.util.Optional;

/** attempt 从 1 开始的三档退避策略：10s、30s、120s。 */
@Component
public class RetryPolicy {

    private static final List<Duration> BACKOFF = List.of(
            Duration.ofSeconds(10), Duration.ofSeconds(30), Duration.ofSeconds(120));

    private final RetryProperties properties;

    public RetryPolicy(RetryProperties properties) {
        this.properties = properties;
    }

    /** 当前 attempt 失败后是否还可创建下一次执行。 */
    public boolean canRetry(int currentAttempt) {
        return currentAttempt > 0
                && currentAttempt < properties.maxAttempts()
                && currentAttempt <= BACKOFF.size();
    }

    /** 当前 attempt 失败后的等待时间；耗尽次数时返回 empty。 */
    public Optional<Duration> backoffAfter(int currentAttempt) {
        return canRetry(currentAttempt)
                ? Optional.of(BACKOFF.get(currentAttempt - 1))
                : Optional.empty();
    }

    public int maxAttempts() {
        return properties.maxAttempts();
    }
}

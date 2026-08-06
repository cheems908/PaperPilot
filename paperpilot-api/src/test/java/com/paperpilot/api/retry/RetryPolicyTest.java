package com.paperpilot.api.retry;

import org.junit.jupiter.api.Test;

import java.time.Duration;

import static org.assertj.core.api.Assertions.assertThat;

class RetryPolicyTest {

    private final RetryPolicy policy = new RetryPolicy(new RetryProperties());

    @Test
    void usesThreeBackoffTiersAndStopsAfterFourthExecution() {
        assertThat(policy.backoffAfter(1)).contains(Duration.ofSeconds(10));
        assertThat(policy.backoffAfter(2)).contains(Duration.ofSeconds(30));
        assertThat(policy.backoffAfter(3)).contains(Duration.ofSeconds(120));
        assertThat(policy.backoffAfter(4)).isEmpty();
        assertThat(policy.maxAttempts()).isEqualTo(4);
    }

    @Test
    void rejectsInvalidAttemptBoundaries() {
        assertThat(policy.canRetry(0)).isFalse();
        assertThat(policy.canRetry(-1)).isFalse();
        assertThat(policy.canRetry(5)).isFalse();
    }

    @Test
    void configuredLowerMaximumIsHonored() {
        RetryPolicy limited = new RetryPolicy(new RetryProperties(2, 10, Duration.ofSeconds(1)));
        assertThat(limited.backoffAfter(1)).contains(Duration.ofSeconds(10));
        assertThat(limited.backoffAfter(2)).isEmpty();
    }
}

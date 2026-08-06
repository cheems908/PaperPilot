package com.paperpilot.api.retry;

import java.time.LocalDateTime;

/** 阶段失败落库后的正式决策。 */
public record StageFailureOutcome(boolean retryScheduled, LocalDateTime nextRetryAt) {

    public static StageFailureOutcome retryAt(LocalDateTime nextRetryAt) {
        return new StageFailureOutcome(true, nextRetryAt);
    }

    public static StageFailureOutcome terminal() {
        return new StageFailureOutcome(false, null);
    }
}

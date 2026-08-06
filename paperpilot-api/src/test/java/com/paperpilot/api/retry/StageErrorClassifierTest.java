package com.paperpilot.api.retry;

import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.ValueSource;

import static org.assertj.core.api.Assertions.assertThat;

class StageErrorClassifierTest {

    private final StageErrorClassifier classifier = new StageErrorClassifier();

    @ParameterizedTest
    @ValueSource(strings = {
            "WORKER_TIMEOUT", "WORKER_UNAVAILABLE", "GROBID_UNAVAILABLE",
            "GITHUB_TEMPORARY_FAILURE", "LLM_RATE_LIMITED"
    })
    void retryableBaseline(String code) {
        assertThat(classifier.isRetryable(code)).isTrue();
        assertThat(classifier.isKnown(code)).isTrue();
    }

    @ParameterizedTest
    @ValueSource(strings = {
            "INVALID_PDF", "INVALID_GITHUB_URL", "UNSUPPORTED_REPOSITORY",
            "INVALID_WORKER_RESPONSE", "TASK_CANCELLED"
    })
    void nonRetryableBaseline(String code) {
        assertThat(classifier.isRetryable(code)).isFalse();
        assertThat(classifier.isKnown(code)).isTrue();
    }

    @ParameterizedTest
    @CsvSource({
            "TIMEOUT,WORKER_TIMEOUT",
            "CONNECTION_ERROR,WORKER_UNAVAILABLE",
            "HTTP_5XX,WORKER_UNAVAILABLE",
            "INVALID_RESPONSE,INVALID_WORKER_RESPONSE"
    })
    void normalizesJavaClientErrors(String localCode, String stableCode) {
        assertThat(classifier.normalize(localCode)).isEqualTo(stableCode);
    }

    @ParameterizedTest
    @ValueSource(strings = {"UNKNOWN", "RESULT_SAVE_FAILED", "SOMETHING_NEW"})
    void unknownErrorsFailClosed(String code) {
        assertThat(classifier.isKnown(code)).isFalse();
        assertThat(classifier.isRetryable(code)).isFalse();
    }
}

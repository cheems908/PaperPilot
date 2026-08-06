package com.paperpilot.api.retry;

import org.springframework.stereotype.Component;

import java.util.Set;
import java.util.Map;

/**
 * 阶段错误的唯一重试分类入口。
 *
 * <p>分类基于稳定 errorCode，而不是 HTTP 状态或 Worker 自报的 retryable 标记，
 * 避免永久业务错误被错误地重新入队。未知错误默认不可重试（fail closed）。
 */
@Component
public class StageErrorClassifier {

    private static final Set<String> RETRYABLE = Set.of(
            "WORKER_TIMEOUT",
            "WORKER_UNAVAILABLE",
            "GROBID_UNAVAILABLE",
            "GITHUB_TEMPORARY_FAILURE",
            "LLM_RATE_LIMITED",
            "EXECUTION_LEASE_EXPIRED");

    private static final Set<String> NON_RETRYABLE = Set.of(
            "INVALID_PDF",
            "INVALID_GITHUB_URL",
            "UNSUPPORTED_REPOSITORY",
            "INVALID_WORKER_RESPONSE",
            "TASK_CANCELLED");

    private static final Map<String, String> JAVA_ERROR_ALIASES = Map.of(
            "TIMEOUT", "WORKER_TIMEOUT",
            "CONNECTION_ERROR", "WORKER_UNAVAILABLE",
            "HTTP_5XX", "WORKER_UNAVAILABLE",
            "INVALID_RESPONSE", "INVALID_WORKER_RESPONSE");

    /** 把 Java HTTP 客户端分类收敛到跨服务稳定错误码。 */
    public String normalize(String errorCode) {
        if (errorCode == null || errorCode.isBlank()) {
            return "UNKNOWN";
        }
        return JAVA_ERROR_ALIASES.getOrDefault(errorCode, errorCode);
    }

    public boolean isRetryable(String errorCode) {
        return RETRYABLE.contains(normalize(errorCode));
    }

    public boolean isKnown(String errorCode) {
        String normalized = normalize(errorCode);
        return RETRYABLE.contains(normalized) || NON_RETRYABLE.contains(normalized);
    }
}

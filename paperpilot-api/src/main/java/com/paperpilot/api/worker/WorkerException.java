package com.paperpilot.api.worker;

/**
 * Worker 调用异常：携带稳定错误分类与 {@code retryable} 标记.
 *
 * <p>业务代码不得 catch 后当作成功返回；编排方依据 {@link #isRetryable()} 决定是否重试。
 */
public class WorkerException extends RuntimeException {

    private final WorkerErrorCode errorCode;
    private final int httpStatus;
    private final boolean retryable;

    public WorkerException(WorkerErrorCode errorCode, int httpStatus, String message) {
        this(errorCode, httpStatus, message, null);
    }

    public WorkerException(WorkerErrorCode errorCode, int httpStatus, String message, Throwable cause) {
        super(message, cause);
        this.errorCode = errorCode;
        this.httpStatus = httpStatus;
        this.retryable = errorCode.isRetryable();
    }

    public WorkerErrorCode getErrorCode() {
        return errorCode;
    }

    public int getHttpStatus() {
        return httpStatus;
    }

    public boolean isRetryable() {
        return retryable;
    }
}

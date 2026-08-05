package com.paperpilot.api.worker;

/**
 * Worker 调用异常：携带稳定分类、远端 errorCode 与 {@code retryable} 标记.
 *
 * <p>{@code remoteErrorCode} 来自 Worker 错误响应体（如 {@code INVALID_PDF}）；
 * {@code retryable} 优先采用远端标记，缺省回退到 {@link WorkerErrorCode} 的默认值。
 * 业务代码不得 catch 后当作成功返回。
 */
public class WorkerException extends RuntimeException {

    private final WorkerErrorCode errorCode;
    private final String remoteErrorCode;
    private final int httpStatus;
    private final boolean retryable;

    public WorkerException(WorkerErrorCode errorCode, int httpStatus, String message) {
        this(errorCode, httpStatus, null, errorCode.isRetryable(), message, null);
    }

    public WorkerException(WorkerErrorCode errorCode, int httpStatus, String message, Throwable cause) {
        this(errorCode, httpStatus, null, errorCode.isRetryable(), message, cause);
    }

    public WorkerException(WorkerErrorCode errorCode, int httpStatus, String remoteErrorCode,
                           boolean retryable, String message, Throwable cause) {
        super(message, cause);
        this.errorCode = errorCode;
        this.remoteErrorCode = remoteErrorCode;
        this.httpStatus = httpStatus;
        this.retryable = retryable;
    }

    public WorkerErrorCode getErrorCode() {
        return errorCode;
    }

    /** Worker 返回的业务错误码；未解析到时为 {@code null}。 */
    public String getRemoteErrorCode() {
        return remoteErrorCode;
    }

    public int getHttpStatus() {
        return httpStatus;
    }

    public boolean isRetryable() {
        return retryable;
    }
}

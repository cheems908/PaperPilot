package com.paperpilot.api.worker;

/**
 * Worker 调用错误的稳定分类；{@code retryable} 供编排方决定是否重试.
 */
public enum WorkerErrorCode {

    /** DNS 解析失败、连接被拒绝等传输层错误 */
    CONNECTION_ERROR(true),

    /** 连接/读取超时 */
    TIMEOUT(true),

    /** 4xx 客户端错误（请求契约与 Worker 不符，重试无意义） */
    HTTP_4XX(false),

    /** 5xx 服务端错误（Worker 暂时不可用） */
    HTTP_5XX(true),

    /** 非法 JSON / 必填字段缺失 / 响应体过大（契约不符） */
    INVALID_RESPONSE(false),

    /** Worker 显式返回 {@code success=false}（阶段业务失败） */
    BUSINESS_FAILED(false);

    private final boolean retryable;

    WorkerErrorCode(boolean retryable) {
        this.retryable = retryable;
    }

    public boolean isRetryable() {
        return retryable;
    }
}

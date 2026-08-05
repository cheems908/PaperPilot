package com.paperpilot.api.mq;

/**
 * 阶段消息发送失败（MQ 不可用、序列化失败等）.
 *
 * <p>由 {@link AnalysisTaskDispatcher} 捕获并记录标识信息后吞掉，
 * 任务保持 QUEUED 不标记成功；已知一致性窗口由 T7 Outbox 兜底。
 */
public class StageDispatchException extends RuntimeException {

    public StageDispatchException(String message) {
        super(message);
    }

    public StageDispatchException(String message, Throwable cause) {
        super(message, cause);
    }
}

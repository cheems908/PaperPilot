package com.paperpilot.api.worker;

import com.paperpilot.api.dto.worker.WorkerStageRequest;
import com.paperpilot.api.dto.worker.WorkerStageResponse;

/**
 * 阶段无关的 Worker 调用客户端：把 {@code TaskStage} 映射到 Python 内部接口.
 *
 * <p>只负责传输与响应校验，不修改 task/stage 状态、不实现自动重试或熔断
 * （留给编排方/后续卡）。
 */
public interface WorkerClient {

    /** 执行单阶段；失败抛 {@link WorkerException}（含稳定错误码与 retryable 标记）。 */
    WorkerStageResponse execute(WorkerStageRequest request);
}

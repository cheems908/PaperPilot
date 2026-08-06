package com.paperpilot.api.service;

/**
 * 单阶段编排结果：消费方据此决定 ACK 语义；失败可能已进入 WAITING_RETRY，
 * 后续由 MySQL 到期扫描器重新派发，当前 MQ 消息仍然 ACK。
 *
 * <p>{@code skipped=true} 表示未调用 Worker（幂等命中 / 未获得执行权 / 引用不符）。
 */
public record StageExecutionResult(
        Long stageExecutionId,
        boolean workerCalled,
        boolean success,
        boolean skipped,
        String detail) {

    /** 幂等/未抢占/引用不符等未执行场景。 */
    public static StageExecutionResult skipped(StageExecutionContext ctx, String detail) {
        return new StageExecutionResult(ctx == null ? null : ctx.stage().getId(),
                false, false, true, detail);
    }

    /** Worker 成功且结果原子落库、阶段标记 SUCCEEDED。 */
    public static StageExecutionResult succeeded(StageExecutionContext ctx) {
        return new StageExecutionResult(ctx.stage().getId(), true, true, false, null);
    }

    /** Worker 失败或结果落库失败：阶段进入 WAITING_RETRY 或 FAILED，错误可查询。 */
    public static StageExecutionResult failed(StageExecutionContext ctx, String errorCode, String detail) {
        return new StageExecutionResult(ctx.stage().getId(), true, false, false,
                errorCode + (detail == null || detail.isBlank() ? "" : ": " + detail));
    }
}

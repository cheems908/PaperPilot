package com.paperpilot.api.service;

/**
 * 单阶段编排结果：消费方据此决定 ACK 语义；具体重试调度属 T4.
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

    /** Worker 失败或结果落库失败：阶段标记 FAILED（不标 SUCCEEDED），错误可查询。 */
    public static StageExecutionResult failed(StageExecutionContext ctx, String errorCode, String detail) {
        return new StageExecutionResult(ctx.stage().getId(), true, false, false,
                errorCode + (detail == null || detail.isBlank() ? "" : ": " + detail));
    }
}

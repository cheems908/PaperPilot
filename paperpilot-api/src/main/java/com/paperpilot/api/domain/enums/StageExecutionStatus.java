package com.paperpilot.api.domain.enums;

/**
 * 阶段执行状态（与任务总状态 {@link TaskStatus} 独立）.
 *
 * <p>相比 TaskStatus 去掉 QUEUED（阶段不排队，入队后直接 RUNNING），
 * 新增 SKIPPED（阶段被跳过，如仓库缺失时跳过克隆阶段）。
 */
public enum StageExecutionStatus {
    /** 已创建，等待执行 */
    PENDING,
    /** 执行中 */
    RUNNING,
    /** 成功（终态） */
    SUCCEEDED,
    /** 等待重试（失败后可重入 RUNNING） */
    WAITING_RETRY,
    /** 失败（终态） */
    FAILED,
    /** 取消（终态） */
    CANCELLED,
    /** 跳过（终态） */
    SKIPPED
}

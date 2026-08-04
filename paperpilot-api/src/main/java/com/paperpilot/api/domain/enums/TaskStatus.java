package com.paperpilot.api.domain.enums;

/**
 * 任务总状态.
 *
 * <p>状态迁移必须经 {@link com.paperpilot.api.domain.TaskStateMachine} 校验（Java 侧控制）。
 *
 * <pre>
 * PENDING → QUEUED → RUNNING → SUCCEEDED
 *                        ├──→ WAITING_RETRY → RUNNING
 *                        ├──→ FAILED
 *                        └──→ CANCELLED
 * </pre>
 */
public enum TaskStatus {
    /** 已创建，等待入队 */
    PENDING,
    /** 已入队，等待执行 */
    QUEUED,
    /** 执行中 */
    RUNNING,
    /** 等待重试（失败后可重入 RUNNING） */
    WAITING_RETRY,
    /** 成功（终态） */
    SUCCEEDED,
    /** 失败（终态） */
    FAILED,
    /** 取消（终态） */
    CANCELLED
}

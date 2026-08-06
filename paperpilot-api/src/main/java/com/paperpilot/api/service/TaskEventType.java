package com.paperpilot.api.service;

/** 任务事件类型常量（前端 taskEventsPolicy 依赖终态类型判定断连）. */
public final class TaskEventType {

    public static final String SNAPSHOT = "task-snapshot";
    public static final String STAGE_STARTED = "stage-started";
    public static final String STAGE_PROGRESS = "stage-progress";
    public static final String STAGE_COMPLETED = "stage-completed";
    public static final String STAGE_RETRYING = "stage-retrying";
    public static final String STAGE_RECOVERED = "stage-recovered";
    public static final String TASK_COMPLETED = "task-completed";
    public static final String TASK_FAILED = "task-failed";
    public static final String TASK_CANCELLED = "task-cancelled";
    public static final String HEARTBEAT = "heartbeat";

    private TaskEventType() {
    }
}

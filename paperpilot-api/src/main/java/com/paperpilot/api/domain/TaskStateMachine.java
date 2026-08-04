package com.paperpilot.api.domain;

import com.paperpilot.api.domain.enums.TaskStatus;

import java.util.EnumMap;
import java.util.EnumSet;
import java.util.Map;
import java.util.Set;

/**
 * 任务状态机 — 所有任务状态迁移必须经过本类校验（Java 侧控制）.
 *
 * <p>允许的迁移：
 * <pre>
 * PENDING → QUEUED → RUNNING → SUCCEEDED
 *                        ├──→ WAITING_RETRY → RUNNING
 *                        ├──→ FAILED
 *                        └──→ CANCELLED
 * </pre>
 * 终态（SUCCEEDED / FAILED / CANCELLED）没有出边；其余非法迁移抛出
 * {@link IllegalStateException}。
 */
public final class TaskStateMachine {

    /** 状态 → 允许迁移到的状态集合 */
    private static final Map<TaskStatus, Set<TaskStatus>> ALLOWED = new EnumMap<>(TaskStatus.class);

    static {
        ALLOWED.put(TaskStatus.PENDING, EnumSet.of(TaskStatus.QUEUED));
        ALLOWED.put(TaskStatus.QUEUED, EnumSet.of(TaskStatus.RUNNING));
        ALLOWED.put(TaskStatus.RUNNING, EnumSet.of(
                TaskStatus.SUCCEEDED, TaskStatus.WAITING_RETRY, TaskStatus.FAILED, TaskStatus.CANCELLED));
        ALLOWED.put(TaskStatus.WAITING_RETRY, EnumSet.of(TaskStatus.RUNNING));
        // SUCCEEDED / FAILED / CANCELLED 为终态，无出边，故不注册
    }

    private TaskStateMachine() {
    }

    /**
     * 校验并返回迁移后的状态；非法迁移抛出 {@link IllegalStateException}.
     *
     * @param from 旧状态
     * @param to   目标状态
     * @return 校验通过后的目标状态
     */
    public static TaskStatus transition(TaskStatus from, TaskStatus to) {
        Set<TaskStatus> allowed = ALLOWED.get(from);
        if (allowed == null) {
            throw new IllegalStateException("Illegal task transition: task in terminal state " + from + " cannot change");
        }
        if (!allowed.contains(to)) {
            throw new IllegalStateException("Illegal task transition: " + from + " -> " + to);
        }
        return to;
    }

    /**
     * 仅判断迁移是否合法，不抛异常.
     */
    public static boolean canTransition(TaskStatus from, TaskStatus to) {
        Set<TaskStatus> allowed = ALLOWED.get(from);
        return allowed != null && allowed.contains(to);
    }
}

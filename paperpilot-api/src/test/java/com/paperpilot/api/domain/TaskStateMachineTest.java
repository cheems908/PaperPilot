package com.paperpilot.api.domain;

import com.paperpilot.api.domain.enums.TaskStatus;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class TaskStateMachineTest {

    @Test
    void allowsAllDocumentedTransitions() {
        // PENDING
        assertThat(TaskStateMachine.transition(TaskStatus.PENDING, TaskStatus.QUEUED))
                .isEqualTo(TaskStatus.QUEUED);
        assertThat(TaskStateMachine.transition(TaskStatus.PENDING, TaskStatus.CANCELLED))
                .isEqualTo(TaskStatus.CANCELLED);
        // QUEUED
        assertThat(TaskStateMachine.transition(TaskStatus.QUEUED, TaskStatus.RUNNING))
                .isEqualTo(TaskStatus.RUNNING);
        assertThat(TaskStateMachine.transition(TaskStatus.QUEUED, TaskStatus.CANCELLED))
                .isEqualTo(TaskStatus.CANCELLED);
        // RUNNING
        assertThat(TaskStateMachine.transition(TaskStatus.RUNNING, TaskStatus.SUCCEEDED))
                .isEqualTo(TaskStatus.SUCCEEDED);
        assertThat(TaskStateMachine.transition(TaskStatus.RUNNING, TaskStatus.WAITING_RETRY))
                .isEqualTo(TaskStatus.WAITING_RETRY);
        assertThat(TaskStateMachine.transition(TaskStatus.RUNNING, TaskStatus.FAILED))
                .isEqualTo(TaskStatus.FAILED);
        assertThat(TaskStateMachine.transition(TaskStatus.RUNNING, TaskStatus.CANCELLED))
                .isEqualTo(TaskStatus.CANCELLED);
        // WAITING_RETRY：自动重试必须先回 QUEUED，或直接取消
        assertThat(TaskStateMachine.transition(TaskStatus.WAITING_RETRY, TaskStatus.QUEUED))
                .isEqualTo(TaskStatus.QUEUED);
        assertThat(TaskStateMachine.transition(TaskStatus.WAITING_RETRY, TaskStatus.CANCELLED))
                .isEqualTo(TaskStatus.CANCELLED);
        // FAILED：仅人工重试回 QUEUED
        assertThat(TaskStateMachine.transition(TaskStatus.FAILED, TaskStatus.QUEUED))
                .isEqualTo(TaskStatus.QUEUED);
    }

    @Test
    void rejectsSkippedStages() {
        assertThatThrownBy(() -> TaskStateMachine.transition(TaskStatus.PENDING, TaskStatus.RUNNING))
                .isInstanceOf(IllegalStateException.class);
        assertThatThrownBy(() -> TaskStateMachine.transition(TaskStatus.QUEUED, TaskStatus.SUCCEEDED))
                .isInstanceOf(IllegalStateException.class);
        // 自动重试必须先回 QUEUED，不能直接 RUNNING
        assertThatThrownBy(() -> TaskStateMachine.transition(TaskStatus.WAITING_RETRY, TaskStatus.RUNNING))
                .isInstanceOf(IllegalStateException.class);
        assertThatThrownBy(() -> TaskStateMachine.transition(TaskStatus.WAITING_RETRY, TaskStatus.FAILED))
                .isInstanceOf(IllegalStateException.class);
        // FAILED 只能人工重试回 QUEUED
        assertThatThrownBy(() -> TaskStateMachine.transition(TaskStatus.FAILED, TaskStatus.RUNNING))
                .isInstanceOf(IllegalStateException.class);
        assertThatThrownBy(() -> TaskStateMachine.transition(TaskStatus.FAILED, TaskStatus.SUCCEEDED))
                .isInstanceOf(IllegalStateException.class);
    }

    @Test
    void rejectsBackwardTransitions() {
        assertThatThrownBy(() -> TaskStateMachine.transition(TaskStatus.RUNNING, TaskStatus.QUEUED))
                .isInstanceOf(IllegalStateException.class);
        assertThatThrownBy(() -> TaskStateMachine.transition(TaskStatus.RUNNING, TaskStatus.PENDING))
                .isInstanceOf(IllegalStateException.class);
        assertThatThrownBy(() -> TaskStateMachine.transition(TaskStatus.SUCCEEDED, TaskStatus.RUNNING))
                .isInstanceOf(IllegalStateException.class);
        // CANCELLED 不可恢复
        assertThatThrownBy(() -> TaskStateMachine.transition(TaskStatus.CANCELLED, TaskStatus.QUEUED))
                .isInstanceOf(IllegalStateException.class);
        assertThatThrownBy(() -> TaskStateMachine.transition(TaskStatus.CANCELLED, TaskStatus.RUNNING))
                .isInstanceOf(IllegalStateException.class);
    }

    @Test
    void rejectsSelfTransitions() {
        assertThatThrownBy(() -> TaskStateMachine.transition(TaskStatus.RUNNING, TaskStatus.RUNNING))
                .isInstanceOf(IllegalStateException.class);
        assertThatThrownBy(() -> TaskStateMachine.transition(TaskStatus.FAILED, TaskStatus.FAILED))
                .isInstanceOf(IllegalStateException.class);
    }

    @Test
    void terminalStatesHaveNoOutgoingEdges() {
        // 仅 SUCCEEDED / CANCELLED 为不可恢复终态；FAILED 可人工重试回 QUEUED
        for (TaskStatus terminal : new TaskStatus[]{TaskStatus.SUCCEEDED, TaskStatus.CANCELLED}) {
            for (TaskStatus target : TaskStatus.values()) {
                assertThat(TaskStateMachine.canTransition(terminal, target))
                        .as("%s -> %s should be forbidden", terminal, target)
                        .isFalse();
            }
        }
        // FAILED 唯一出边是 QUEUED
        assertThat(TaskStateMachine.canTransition(TaskStatus.FAILED, TaskStatus.QUEUED)).isTrue();
        assertThat(TaskStateMachine.canTransition(TaskStatus.FAILED, TaskStatus.CANCELLED)).isFalse();
    }
}

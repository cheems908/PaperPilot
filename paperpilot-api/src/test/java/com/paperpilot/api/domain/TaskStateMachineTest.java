package com.paperpilot.api.domain;

import com.paperpilot.api.domain.enums.TaskStatus;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class TaskStateMachineTest {

    @Test
    void allowsAllDocumentedTransitions() {
        assertThat(TaskStateMachine.transition(TaskStatus.PENDING, TaskStatus.QUEUED))
                .isEqualTo(TaskStatus.QUEUED);
        assertThat(TaskStateMachine.transition(TaskStatus.QUEUED, TaskStatus.RUNNING))
                .isEqualTo(TaskStatus.RUNNING);
        assertThat(TaskStateMachine.transition(TaskStatus.RUNNING, TaskStatus.SUCCEEDED))
                .isEqualTo(TaskStatus.SUCCEEDED);
        assertThat(TaskStateMachine.transition(TaskStatus.RUNNING, TaskStatus.WAITING_RETRY))
                .isEqualTo(TaskStatus.WAITING_RETRY);
        assertThat(TaskStateMachine.transition(TaskStatus.RUNNING, TaskStatus.FAILED))
                .isEqualTo(TaskStatus.FAILED);
        assertThat(TaskStateMachine.transition(TaskStatus.RUNNING, TaskStatus.CANCELLED))
                .isEqualTo(TaskStatus.CANCELLED);
        assertThat(TaskStateMachine.transition(TaskStatus.WAITING_RETRY, TaskStatus.RUNNING))
                .isEqualTo(TaskStatus.RUNNING);
    }

    @Test
    void rejectsSkippedStages() {
        assertThatThrownBy(() -> TaskStateMachine.transition(TaskStatus.PENDING, TaskStatus.RUNNING))
                .isInstanceOf(IllegalStateException.class);
        assertThatThrownBy(() -> TaskStateMachine.transition(TaskStatus.QUEUED, TaskStatus.SUCCEEDED))
                .isInstanceOf(IllegalStateException.class);
        assertThatThrownBy(() -> TaskStateMachine.transition(TaskStatus.WAITING_RETRY, TaskStatus.FAILED))
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
    }

    @Test
    void rejectsSelfTransitions() {
        assertThatThrownBy(() -> TaskStateMachine.transition(TaskStatus.RUNNING, TaskStatus.RUNNING))
                .isInstanceOf(IllegalStateException.class);
    }

    @Test
    void terminalStatesHaveNoOutgoingEdges() {
        for (TaskStatus terminal : new TaskStatus[]{
                TaskStatus.SUCCEEDED, TaskStatus.FAILED, TaskStatus.CANCELLED}) {
            for (TaskStatus target : TaskStatus.values()) {
                if (terminal != target) {
                    assertThat(TaskStateMachine.canTransition(terminal, target))
                            .as("%s -> %s should be forbidden", terminal, target)
                            .isFalse();
                }
            }
        }
    }
}

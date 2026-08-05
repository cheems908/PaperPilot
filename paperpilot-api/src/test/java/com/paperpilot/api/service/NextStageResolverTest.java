package com.paperpilot.api.service;

import com.paperpilot.api.domain.enums.TaskStage;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** 阶段顺序唯一来源：四阶段顺序正确、最后阶段无 next、非 MVP 阶段不可调度. */
class NextStageResolverTest {

    private final NextStageResolver resolver = new NextStageResolver();

    @Test
    void fourStageOrderIsCorrectAndLastHasNoNext() {
        assertThat(resolver.nextOf(TaskStage.PARSE_PAPER)).isEqualTo(TaskStage.CLONE_REPOSITORY);
        assertThat(resolver.nextOf(TaskStage.CLONE_REPOSITORY)).isEqualTo(TaskStage.INDEX_CODE);
        assertThat(resolver.nextOf(TaskStage.INDEX_CODE)).isEqualTo(TaskStage.MAP_CONCEPTS);
        assertThat(resolver.nextOf(TaskStage.MAP_CONCEPTS)).isNull(); // 最后阶段无 next
        assertThat(resolver.isLast(TaskStage.MAP_CONCEPTS)).isTrue();
        assertThat(resolver.isLast(TaskStage.PARSE_PAPER)).isFalse();
    }

    @Test
    void nonMvpStagesAreNotSchedulable() {
        assertThatThrownBy(() -> resolver.nextOf(TaskStage.GENERATE_REPORT))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> resolver.nextOf(TaskStage.ANALYZE_ENVIRONMENT))
                .isInstanceOf(IllegalArgumentException.class);
    }
}

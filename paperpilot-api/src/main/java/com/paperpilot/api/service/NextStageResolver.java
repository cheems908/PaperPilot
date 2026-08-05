package com.paperpilot.api.service;

import com.paperpilot.api.domain.enums.TaskStage;
import org.springframework.stereotype.Component;

/**
 * 阶段顺序唯一来源：
 * {@code PARSE_PAPER → CLONE_REPOSITORY → INDEX_CODE → MAP_CONCEPTS → 任务 SUCCEEDED}.
 *
 * <p>消费者与 service 不得散落多个 switch；非 MVP 阶段调用即抛异常，防止被意外调度。
 */
@Component
public class NextStageResolver {

    /** 返回下一阶段；最后阶段（MAP_CONCEPTS）返回 {@code null}。 */
    public TaskStage nextOf(TaskStage stage) {
        return switch (stage) {
            case PARSE_PAPER -> TaskStage.CLONE_REPOSITORY;
            case CLONE_REPOSITORY -> TaskStage.INDEX_CODE;
            case INDEX_CODE -> TaskStage.MAP_CONCEPTS;
            case MAP_CONCEPTS -> null;
            case ANALYZE_ENVIRONMENT, GENERATE_REPORT ->
                    throw new IllegalArgumentException("非 MVP 阶段不可调度: " + stage);
        };
    }

    /** 是否为最后阶段（无下一阶段）。 */
    public boolean isLast(TaskStage stage) {
        return nextOf(stage) == null;
    }
}

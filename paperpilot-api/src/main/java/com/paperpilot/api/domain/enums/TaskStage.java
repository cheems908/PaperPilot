package com.paperpilot.api.domain.enums;

import java.util.EnumSet;
import java.util.Set;

/**
 * 任务阶段（分析流水线步骤）.
 *
 * <p>MVP 只执行前四个阶段；{@code ANALYZE_ENVIRONMENT}、{@code GENERATE_REPORT}
 * 先保留枚举，后续阶段再实现。
 */
public enum TaskStage {
    /** 解析论文 PDF */
    PARSE_PAPER,
    /** 克隆 GitHub 仓库 */
    CLONE_REPOSITORY,
    /** 代码索引（文件/类/函数/行号） */
    INDEX_CODE,
    /** 概念—代码映射 */
    MAP_CONCEPTS,
    /** 环境分析（保留，MVP 不执行） */
    ANALYZE_ENVIRONMENT,
    /** 生成复现报告（保留，MVP 不执行） */
    GENERATE_REPORT;

    /** MVP 实际执行的前四个阶段 */
    public static final Set<TaskStage> MVP_STAGES =
            EnumSet.of(PARSE_PAPER, CLONE_REPOSITORY, INDEX_CODE, MAP_CONCEPTS);
}

package com.paperpilot.api.domain.entity;

import com.baomidou.mybatisplus.annotation.FieldStrategy;
import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import com.baomidou.mybatisplus.annotation.Version;
import com.paperpilot.api.domain.enums.TaskStatus;
import lombok.Data;

import java.time.LocalDateTime;

/** 分析总任务（状态机核心实体）. */
@Data
@TableName("analysis_task")
public class AnalysisTask {

    @TableId(type = IdType.AUTO)
    private Long id;

    private Long projectId;

    /** 来源上传文件（file.id，可空：任务可能不关联文件） */
    private Long sourceFileId;

    /** 论文（paper.id，可空：任务可能只关联仓库） */
    private Long paperId;

    /** 仓库（repository.id，可空：任务可能只关联论文） */
    private Long repositoryId;

    /** 幂等请求键（唯一约束 uk_task_request_key） */
    private String requestKey;

    /** 任务总状态，迁移必须经 {@code TaskStateMachine} 校验 */
    private TaskStatus status;

    /** 任务完成时间（终态时记录，未完成时为 null） */
    private LocalDateTime finishedAt;

    @TableField(insertStrategy = FieldStrategy.NEVER, updateStrategy = FieldStrategy.NEVER)
    private LocalDateTime createdAt;

    @TableField(insertStrategy = FieldStrategy.NEVER, updateStrategy = FieldStrategy.NEVER)
    private LocalDateTime updatedAt;

    @Version
    private Integer version;
}

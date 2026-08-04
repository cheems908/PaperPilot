package com.paperpilot.api.domain.entity;

import com.baomidou.mybatisplus.annotation.FieldStrategy;
import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import com.baomidou.mybatisplus.annotation.Version;
import com.paperpilot.api.domain.enums.TaskStage;
import com.paperpilot.api.domain.enums.TaskStatus;
import lombok.Data;

import java.time.LocalDateTime;

/** 单阶段执行（含重试次数与快照）. */
@Data
@TableName("stage_execution")
public class StageExecution {

    @TableId(type = IdType.AUTO)
    private Long id;

    private Long taskId;

    private TaskStage stage;

    /** 尝试次数，从 1 开始；重试会新增一行 attempt+1 */
    private Integer attempt;

    /** 阶段状态（复用 TaskStatus） */
    private TaskStatus status;

    /** 阶段快照（JSON） */
    private String snapshot;

    private String errorMessage;

    @TableField(insertStrategy = FieldStrategy.NEVER, updateStrategy = FieldStrategy.NEVER)
    private LocalDateTime createdAt;

    @TableField(insertStrategy = FieldStrategy.NEVER, updateStrategy = FieldStrategy.NEVER)
    private LocalDateTime updatedAt;

    @Version
    private Integer version;
}

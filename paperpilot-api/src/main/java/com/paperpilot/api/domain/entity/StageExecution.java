package com.paperpilot.api.domain.entity;

import com.baomidou.mybatisplus.annotation.FieldStrategy;
import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import com.baomidou.mybatisplus.annotation.Version;
import com.paperpilot.api.domain.enums.StageExecutionStatus;
import com.paperpilot.api.domain.enums.TaskStage;
import lombok.Data;

import java.time.LocalDateTime;

/** 单阶段执行（含重试次数与结构化快照）. */
@Data
@TableName("stage_execution")
public class StageExecution {

    @TableId(type = IdType.AUTO)
    private Long id;

    private Long taskId;

    private TaskStage stage;

    /** 尝试次数，从 1 开始；重试会新增一行 attempt+1 */
    private Integer attempt;

    /** 阶段状态（StageExecutionStatus） */
    private StageExecutionStatus status;

    /** 旧版阶段快照（JSON，兼容保留） */
    private String snapshot;

    private String errorMessage;

    /** 阶段输入快照（StageInputSnapshot JSON） */
    private String inputSnapshot;

    /** 阶段输出快照（StageOutputSnapshot JSON） */
    private String outputSnapshot;

    /** 阶段错误快照（StageErrorSnapshot JSON） */
    private String errorSnapshot;

    /** 阶段开始执行时间 */
    private LocalDateTime startedAt;

    /** 阶段结束时间 */
    private LocalDateTime finishedAt;

    /** 下次重试调度时间 */
    private LocalDateTime nextRetryAt;

    /** 最近心跳时间 */
    private LocalDateTime heartbeatAt;

    @TableField(insertStrategy = FieldStrategy.NEVER, updateStrategy = FieldStrategy.NEVER)
    private LocalDateTime createdAt;

    @TableField(insertStrategy = FieldStrategy.NEVER, updateStrategy = FieldStrategy.NEVER)
    private LocalDateTime updatedAt;

    @Version
    private Integer version;
}

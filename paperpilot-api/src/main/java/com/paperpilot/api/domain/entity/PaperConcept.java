package com.paperpilot.api.domain.entity;

import com.baomidou.mybatisplus.annotation.FieldStrategy;
import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import com.baomidou.mybatisplus.annotation.Version;
import lombok.Data;

import java.time.LocalDateTime;

/** 论文概念及原文证据. */
@Data
@TableName("paper_concept")
public class PaperConcept {

    @TableId(type = IdType.AUTO)
    private Long id;

    private Long paperId;

    private String conceptKey;

    private String conceptName;

    private String aliasesJson;

    private String mentionsJson;

    private String extractorVersion;

    private String decision;

    private String abstentionReason;

    private String evidenceText;

    /** 证据位置（页码/段落） */
    private String evidenceLocation;

    @TableField(insertStrategy = FieldStrategy.NEVER, updateStrategy = FieldStrategy.NEVER)
    private LocalDateTime createdAt;

    @TableField(insertStrategy = FieldStrategy.NEVER, updateStrategy = FieldStrategy.NEVER)
    private LocalDateTime updatedAt;

    @Version
    private Integer version;
}

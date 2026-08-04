package com.paperpilot.api.domain.entity;

import com.baomidou.mybatisplus.annotation.FieldStrategy;
import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import com.baomidou.mybatisplus.annotation.Version;
import lombok.Data;

import java.time.LocalDateTime;

/**
 * GitHub 仓库.
 *
 * <p>表名为 {@code repository}，类名用 {@code GitRepository} 避免与 Spring Data 的
 * {@code Repository} 接口混淆。
 */
@Data
@TableName("repository")
public class GitRepository {

    @TableId(type = IdType.AUTO)
    private Long id;

    private Long projectId;

    private String githubUrl;

    private String branch;

    private String commitSha;

    @TableField(insertStrategy = FieldStrategy.NEVER, updateStrategy = FieldStrategy.NEVER)
    private LocalDateTime createdAt;

    @TableField(insertStrategy = FieldStrategy.NEVER, updateStrategy = FieldStrategy.NEVER)
    private LocalDateTime updatedAt;

    @Version
    private Integer version;
}

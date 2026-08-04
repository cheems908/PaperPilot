package com.paperpilot.api.domain.entity;

import com.baomidou.mybatisplus.annotation.FieldStrategy;
import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import com.baomidou.mybatisplus.annotation.Version;
import lombok.Data;

import java.time.LocalDateTime;

/** 代码符号（文件、类、函数和行号）. */
@Data
@TableName("code_symbol")
public class CodeSymbol {

    @TableId(type = IdType.AUTO)
    private Long id;

    private Long repositoryId;

    private String commitSha;

    private String filePath;

    /** 符号名（类/函数/变量）；与 repository_id+commit_sha+file_path 组成唯一索引 */
    private String symbolName;

    private String symbolType;

    private Integer lineNumber;

    @TableField(insertStrategy = FieldStrategy.NEVER, updateStrategy = FieldStrategy.NEVER)
    private LocalDateTime createdAt;

    @TableField(insertStrategy = FieldStrategy.NEVER, updateStrategy = FieldStrategy.NEVER)
    private LocalDateTime updatedAt;

    @Version
    private Integer version;
}

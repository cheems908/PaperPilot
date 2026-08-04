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
 * 上传的文件资源（本地磁盘存储，MVP 无 MinIO）.
 *
 * <p>与 {@link Paper} 分离：上传产生本记录（含原始文件名 / SHA-256 / 大小），
 * 创建任务时再由 file 创建 paper 行，并把 {@code file.id} 记入
 * {@code analysis_task.source_file_id}，避免把文件资源与论文业务实体混淆。
 */
@Data
@TableName("file")
public class File {

    @TableId(type = IdType.AUTO)
    private Long id;

    /** 原始文件名（如 PatchTST.pdf） */
    private String fileName;

    /** 内容 SHA-256（十六进制） */
    private String sha256;

    /** 文件字节数 */
    private Long size;

    /** 本地磁盘存储路径 */
    private String storagePath;

    @TableField(insertStrategy = FieldStrategy.NEVER, updateStrategy = FieldStrategy.NEVER)
    private LocalDateTime createdAt;

    @TableField(insertStrategy = FieldStrategy.NEVER, updateStrategy = FieldStrategy.NEVER)
    private LocalDateTime updatedAt;

    @Version
    private Integer version;
}

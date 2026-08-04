package com.paperpilot.api.dto.file;

/** 论文文件上传响应. */
public record FileUploadResponse(
        Long fileId,
        String fileName,
        String sha256,
        Long size) {
}

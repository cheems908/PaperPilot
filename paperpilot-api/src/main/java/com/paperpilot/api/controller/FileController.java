package com.paperpilot.api.controller;

import com.paperpilot.api.common.ApiResponse;
import com.paperpilot.api.dto.file.FileUploadResponse;
import com.paperpilot.api.service.FileStorageService;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;

/** 文件接口. */
@RestController
@RequestMapping("/api/v1/files")
@RequiredArgsConstructor
public class FileController {

    private final FileStorageService fileStorageService;

    /** 上传论文 PDF（multipart/form-data，字段名 file）。 */
    @PostMapping("/papers")
    public ApiResponse<FileUploadResponse> uploadPaper(@RequestParam("file") MultipartFile file) {
        return ApiResponse.ok(fileStorageService.upload(file));
    }
}

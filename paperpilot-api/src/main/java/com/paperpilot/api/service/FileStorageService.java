package com.paperpilot.api.service;

import com.paperpilot.api.common.ApiException;
import com.paperpilot.api.common.ErrorCode;
import com.paperpilot.api.domain.entity.File;
import com.paperpilot.api.dto.file.FileUploadResponse;
import com.paperpilot.api.mapper.FileMapper;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;
import org.springframework.web.multipart.MultipartFile;

import java.io.FileOutputStream;
import java.io.IOException;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.Locale;
import java.util.Set;

/**
 * 论文文件存储（MVP：本地磁盘，无 MinIO）.
 *
 * <p>文件按内容 SHA-256 命名去重存储，元数据（原始文件名/SHA-256/大小/路径）
 * 写入 {@code file} 表。仅接收 PDF。
 */
@Service
public class FileStorageService {

    private static final long MAX_FILE_SIZE = 50L * 1024 * 1024; // 50MB
    private static final Set<String> ALLOWED_EXTENSIONS = Set.of("pdf");

    private final FileMapper fileMapper;
    private final String storageDir;

    public FileStorageService(FileMapper fileMapper,
            @Value("${paperpilot.storage.local-dir:./uploads}") String storageDir) {
        this.fileMapper = fileMapper;
        this.storageDir = storageDir;
    }

    /** 接收上传的论文文件：落盘 + 写 file 表，返回上传响应。 */
    public FileUploadResponse upload(MultipartFile multipartFile) {
        if (multipartFile == null || multipartFile.isEmpty()) {
            throw new ApiException(ErrorCode.BAD_REQUEST, "上传文件为空");
        }
        if (multipartFile.getSize() > MAX_FILE_SIZE) {
            throw new ApiException(ErrorCode.BAD_REQUEST, "文件超过 50MB 限制");
        }
        String originalName = StringUtils.cleanPath(
                multipartFile.getOriginalFilename() == null ? "unnamed" : multipartFile.getOriginalFilename());
        String extension = extensionOf(originalName);
        if (!ALLOWED_EXTENSIONS.contains(extension)) {
            throw new ApiException(ErrorCode.BAD_REQUEST, "仅支持 PDF 文件");
        }

        byte[] bytes;
        try {
            bytes = multipartFile.getBytes();
        } catch (IOException e) {
            throw new ApiException(ErrorCode.INTERNAL, "读取上传文件失败");
        }
        if (bytes.length > MAX_FILE_SIZE) {
            throw new ApiException(ErrorCode.BAD_REQUEST, "文件超过 50MB 限制");
        }

        String sha256 = sha256(bytes);
        java.io.File target = new java.io.File(storageDir, sha256 + "." + extension);
        if (!target.exists()) {
            writeFile(target, bytes);
        }

        File record = new File();
        record.setFileName(originalName);
        record.setSha256(sha256);
        record.setSize((long) bytes.length);
        record.setStoragePath(target.getAbsolutePath());
        fileMapper.insert(record);

        return new FileUploadResponse(record.getId(), originalName, sha256, (long) bytes.length);
    }

    private void writeFile(java.io.File target, byte[] bytes) {
        java.io.File dir = target.getParentFile();
        if (dir != null && !dir.exists() && !dir.mkdirs()) {
            throw new ApiException(ErrorCode.INTERNAL, "存储目录创建失败: " + dir);
        }
        try (FileOutputStream out = new FileOutputStream(target)) {
            out.write(bytes);
        } catch (IOException e) {
            throw new ApiException(ErrorCode.INTERNAL, "文件写入失败: " + target);
        }
    }

    private String extensionOf(String name) {
        int idx = name.lastIndexOf('.');
        if (idx < 0 || idx == name.length() - 1) {
            return "";
        }
        return name.substring(idx + 1).toLowerCase(Locale.ROOT);
    }

    private String sha256(byte[] data) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            return HexFormat.of().formatHex(digest.digest(data));
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 algorithm not available", e);
        }
    }
}

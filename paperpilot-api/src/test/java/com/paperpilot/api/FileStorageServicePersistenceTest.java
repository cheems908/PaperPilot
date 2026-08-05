package com.paperpilot.api;

import com.paperpilot.api.common.ApiException;
import com.paperpilot.api.domain.entity.File;
import com.paperpilot.api.dto.file.FileUploadResponse;
import com.paperpilot.api.mapper.FileMapper;
import com.paperpilot.api.service.FileStorageService;
import org.apache.ibatis.session.SqlSession;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.mock.web.MockMultipartFile;
import org.testcontainers.containers.MySQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import javax.sql.DataSource;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.util.HexFormat;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * 文件上传服务端到端验证：SHA-256/大小计算、落盘、file 表写入、扩展名与空文件校验.
 */
@Testcontainers
class FileStorageServicePersistenceTest {

    @Container
    static final MySQLContainer<?> MYSQL = new MySQLContainer<>("mysql:8.0");

    @TempDir
    Path tempDir;

    @Test
    void uploadWritesFileAndMetadata() throws Exception {
        DataSource ds = TestSupport.dataSource(MYSQL);
        Flyway.configure().dataSource(ds).load().migrate();

        try (SqlSession session = TestSupport.buildFactory(ds).openSession(true)) {
            FileMapper fileMapper = session.getMapper(FileMapper.class);
            FileStorageService fileStorageService = new FileStorageService(fileMapper, tempDir.toString());

            byte[] bytes = "hello paperpilot".getBytes();
            MockMultipartFile multipart =
                    new MockMultipartFile("file", "PatchTST.pdf", "application/pdf", bytes);

            FileUploadResponse resp = fileStorageService.upload(multipart);

            String expectedSha = HexFormat.of().formatHex(
                    MessageDigest.getInstance("SHA-256").digest(bytes));
            assertThat(resp.sha256()).isEqualTo(expectedSha);
            assertThat(resp.size()).isEqualTo((long) bytes.length);
            assertThat(resp.fileName()).isEqualTo("PatchTST.pdf");
            assertThat(resp.fileId()).isNotNull();

            // 落盘：storagePath 为相对 storage root 的逻辑路径，解析后存在且内容一致
            File record = fileMapper.selectById(resp.fileId());
            assertThat(record.getStoragePath()).isNotNull();
            assertThat(record.getStoragePath()).isEqualTo(expectedSha + ".pdf");
            assertThat(Path.of(record.getStoragePath()).isAbsolute()).isFalse();
            java.io.File stored = tempDir.resolve(record.getStoragePath()).toFile();
            assertThat(stored).exists();
            assertThat(java.nio.file.Files.readAllBytes(stored.toPath())).isEqualTo(bytes);

            // 同内容再次上传：文件去重（不重复写盘），但新增独立记录
            FileUploadResponse again = fileStorageService.upload(multipart);
            assertThat(again.fileId()).isNotEqualTo(resp.fileId());
            assertThat(fileMapper.selectList(null)).hasSize(2);
        }
    }

    @Test
    void rejectsNonPdfAndEmpty() throws Exception {
        DataSource ds = TestSupport.dataSource(MYSQL);
        Flyway.configure().dataSource(ds).load().migrate();

        try (SqlSession session = TestSupport.buildFactory(ds).openSession(true)) {
            FileMapper fileMapper = session.getMapper(FileMapper.class);
            FileStorageService fileStorageService = new FileStorageService(fileMapper, tempDir.toString());

            // 容器为 static 跨方法共享，故用相对计数而非绝对为空
            long before = fileMapper.selectCount(null);

            MockMultipartFile txt =
                    new MockMultipartFile("file", "notes.txt", "text/plain", "x".getBytes());
            assertThatThrownBy(() -> fileStorageService.upload(txt))
                    .isInstanceOf(ApiException.class)
                    .hasMessageContaining("仅支持 PDF");

            MockMultipartFile empty = new MockMultipartFile("file", "a.pdf", "application/pdf", new byte[0]);
            assertThatThrownBy(() -> fileStorageService.upload(empty))
                    .isInstanceOf(ApiException.class)
                    .hasMessageContaining("为空");

            assertThat(fileMapper.selectCount(null)).isEqualTo(before);
        }
    }
}

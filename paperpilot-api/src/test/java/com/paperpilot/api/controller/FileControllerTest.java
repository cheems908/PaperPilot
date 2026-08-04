package com.paperpilot.api.controller;

import com.paperpilot.api.common.GlobalExceptionHandler;
import com.paperpilot.api.dto.file.FileUploadResponse;
import com.paperpilot.api.service.FileStorageService;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.context.annotation.Import;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.test.web.servlet.MockMvc;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.multipart;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/** 文件上传接口：multipart 路由与信封. */
@WebMvcTest(FileController.class)
@Import(GlobalExceptionHandler.class)
class FileControllerTest {

    @Autowired
    MockMvc mockMvc;

    @MockBean
    FileStorageService fileStorageService;

    @Test
    void uploadPaper() throws Exception {
        when(fileStorageService.upload(any()))
                .thenReturn(new FileUploadResponse(1L, "a.pdf", "abc", 3L));
        mockMvc.perform(multipart("/api/v1/files/papers")
                        .file(new MockMultipartFile("file", "a.pdf", "application/pdf", new byte[]{1, 2, 3})))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(0))
                .andExpect(jsonPath("$.data.fileId").value(1))
                .andExpect(jsonPath("$.data.fileName").value("a.pdf"))
                .andExpect(jsonPath("$.data.sha256").value("abc"))
                .andExpect(jsonPath("$.data.size").value(3));
    }
}

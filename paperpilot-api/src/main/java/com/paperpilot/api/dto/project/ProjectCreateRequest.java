package com.paperpilot.api.dto.project;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

/** 创建项目请求体. */
public record ProjectCreateRequest(
        @NotBlank(message = "项目名称不能为空")
        @Size(max = 128, message = "项目名称最长 128 字符")
        String name,

        String description) {
}

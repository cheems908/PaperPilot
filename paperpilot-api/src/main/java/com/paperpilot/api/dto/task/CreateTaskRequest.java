package com.paperpilot.api.dto.task;

import jakarta.validation.constraints.Size;

/**
 * 创建分析任务请求体.
 *
 * <p>{@code fileId} 与 {@code githubUrl} 至少提供一个（合法组合由服务层校验）：
 * <ul>
 *   <li>只有 {@code fileId}：任务只关联论文（由文件解析出 paper）</li>
 *   <li>只有 {@code githubUrl}：任务只关联仓库</li>
 *   <li>两者都有：任务同时关联论文与仓库</li>
 * </ul>
 *
 * @param fileId     已上传文件（file.id）
 * @param githubUrl  GitHub 仓库地址
 * @param branch     仓库分支（默认 main）
 * @param requestKey 幂等请求键（可选，缺省由服务端生成）
 */
public record CreateTaskRequest(
        Long fileId,

        @Size(max = 1024, message = "GitHub URL 最长 1024 字符")
        String githubUrl,

        @Size(max = 255, message = "分支名最长 255 字符")
        String branch,

        @Size(max = 128, message = "requestKey 最长 128 字符")
        String requestKey) {
}

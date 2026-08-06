package com.paperpilot.api.controller;

import com.paperpilot.api.common.ApiResponse;
import com.paperpilot.api.dto.task.CreateTaskRequest;
import com.paperpilot.api.dto.task.StageResponse;
import com.paperpilot.api.dto.task.TaskDetailResponse;
import com.paperpilot.api.dto.task.TaskResponse;
import com.paperpilot.api.dto.task.TaskResultResponse;
import com.paperpilot.api.service.AnalysisTaskService;
import com.paperpilot.api.service.StageExecutionService;
import com.paperpilot.api.service.TaskEventService;
import com.paperpilot.api.service.TaskResultService;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import java.util.List;

/** 分析任务接口. */
@RestController
@RequestMapping("/api/v1")
@RequiredArgsConstructor
public class TaskController {

    private final AnalysisTaskService analysisTaskService;
    private final StageExecutionService stageExecutionService;
    private final TaskResultService taskResultService;
    private final TaskEventService taskEventService;

    /** 创建分析任务（幂等，202 Accepted）。 */
    @PostMapping("/projects/{projectId}/analysis-tasks")
    public ResponseEntity<ApiResponse<TaskResponse>> createTask(
            @PathVariable Long projectId,
            @Valid @RequestBody CreateTaskRequest request) {
        TaskResponse response = analysisTaskService.createTask(projectId, request);
        return ResponseEntity.status(HttpStatus.ACCEPTED).body(ApiResponse.ok(response));
    }

    @GetMapping("/tasks/{taskId}")
    public ApiResponse<TaskDetailResponse> getTask(@PathVariable Long taskId) {
        return ApiResponse.ok(analysisTaskService.getTask(taskId));
    }

    @GetMapping("/tasks/{taskId}/stages")
    public ApiResponse<List<StageResponse>> getStages(@PathVariable Long taskId) {
        return ApiResponse.ok(stageExecutionService.listResponses(taskId));
    }

    @GetMapping("/tasks/{taskId}/result")
    public ApiResponse<TaskResultResponse> getResult(@PathVariable Long taskId) {
        return ApiResponse.ok(taskResultService.getResult(taskId));
    }

    /** 任务事件流（SSE）：连接先推送当前 snapshot（MySQL + Redis），再收后续事件。 */
    @GetMapping(value = "/tasks/{taskId}/events", produces = MediaType.TEXT_EVENT_STREAM_VALUE)
    public SseEmitter getEvents(@PathVariable Long taskId) {
        return taskEventService.subscribe(taskId);
    }

    @PostMapping("/tasks/{taskId}/cancel")
    public ApiResponse<TaskDetailResponse> cancel(@PathVariable Long taskId) {
        return ApiResponse.ok(analysisTaskService.cancel(taskId));
    }

    @PostMapping("/tasks/{taskId}/retry")
    public ApiResponse<TaskDetailResponse> retry(@PathVariable Long taskId) {
        return ApiResponse.ok(analysisTaskService.retry(taskId));
    }
}

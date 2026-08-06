package com.paperpilot.api.controller;

import com.paperpilot.api.common.GlobalExceptionHandler;
import com.paperpilot.api.domain.entity.AnalysisTask;
import com.paperpilot.api.domain.enums.TaskStatus;
import com.paperpilot.api.dto.task.StageResponse;
import com.paperpilot.api.dto.task.TaskDetailResponse;
import com.paperpilot.api.dto.task.TaskResponse;
import com.paperpilot.api.dto.task.TaskResultResponse;
import com.paperpilot.api.service.AnalysisTaskService;
import com.paperpilot.api.service.StageExecutionService;
import com.paperpilot.api.service.TaskEventService;
import com.paperpilot.api.service.TaskResultService;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import java.util.List;
import java.util.Map;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.request;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/** 任务接口：创建 202 / 查询 / stages / result / cancel / retry / SSE. */
@WebMvcTest(TaskController.class)
@Import(GlobalExceptionHandler.class)
class TaskControllerTest {

    @Autowired
    MockMvc mockMvc;

    @MockBean
    AnalysisTaskService analysisTaskService;

    @MockBean
    StageExecutionService stageExecutionService;

    @MockBean
    TaskResultService taskResultService;

    @MockBean
    TaskEventService taskEventService;

    @Test
    void createTaskReturns202() throws Exception {
        when(analysisTaskService.createTask(eq(1L), any()))
                .thenReturn(new TaskResponse(7L, "QUEUED", "/api/v1/tasks/7/events"));
        mockMvc.perform(post("/api/v1/projects/1/analysis-tasks")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"githubUrl\":\"https://github.com/a/b\"}"))
                .andExpect(status().isAccepted())
                .andExpect(jsonPath("$.code").value(0))
                .andExpect(jsonPath("$.data.taskId").value(7))
                .andExpect(jsonPath("$.data.status").value("QUEUED"))
                .andExpect(jsonPath("$.data.eventsUrl").value("/api/v1/tasks/7/events"));
    }

    @Test
    void getTask() throws Exception {
        when(analysisTaskService.getTask(7L))
                .thenReturn(new TaskDetailResponse(7L, 1L, null, null, null, "QUEUED", "k", null, null));
        mockMvc.perform(get("/api/v1/tasks/7"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(0))
                .andExpect(jsonPath("$.data.id").value(7))
                .andExpect(jsonPath("$.data.status").value("QUEUED"));
    }

    @Test
    void getStages() throws Exception {
        when(stageExecutionService.listResponses(7L))
                .thenReturn(List.of(new StageResponse("PARSE_PAPER", 1, "PENDING", null, null, null)));
        mockMvc.perform(get("/api/v1/tasks/7/stages"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data[0].stage").value("PARSE_PAPER"))
                .andExpect(jsonPath("$.data[0].attempt").value(1));
    }

    @Test
    void getResult() throws Exception {
        when(taskResultService.getResult(7L))
                .thenReturn(new TaskResultResponse(7L, "QUEUED", null, null, List.of(), Map.of(),
                        "NO_MAPPINGS", List.of()));
        mockMvc.perform(get("/api/v1/tasks/7/result"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.taskId").value(7))
                .andExpect(jsonPath("$.data.status").value("QUEUED"))
                .andExpect(jsonPath("$.data.mappingStatus").value("NO_MAPPINGS"));
    }

    @Test
    void cancelAndRetry() throws Exception {
        when(analysisTaskService.cancel(7L))
                .thenReturn(new TaskDetailResponse(7L, 1L, null, null, null, "CANCELLED", "k", null, null));
        when(analysisTaskService.retry(7L))
                .thenReturn(new TaskDetailResponse(7L, 1L, null, null, null, "QUEUED", "k", null, null));

        mockMvc.perform(post("/api/v1/tasks/7/cancel"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.status").value("CANCELLED"));
        mockMvc.perform(post("/api/v1/tasks/7/retry"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.status").value("QUEUED"));
    }

    @Test
    void eventsIsSse() throws Exception {
        when(taskEventService.subscribe(7L)).thenReturn(new SseEmitter());

        mockMvc.perform(get("/api/v1/tasks/7/events").accept(MediaType.TEXT_EVENT_STREAM))
                .andExpect(request().asyncStarted());
    }
}

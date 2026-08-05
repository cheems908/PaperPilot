package com.paperpilot.api.service;

import com.paperpilot.api.domain.entity.AnalysisTask;
import com.paperpilot.api.domain.entity.StageExecution;
import com.paperpilot.api.dto.mq.StageTaskMessage;

/**
 * 阶段编排上下文：编排器加载的任务、阶段与消息引用.
 */
public record StageExecutionContext(AnalysisTask task, StageExecution stage, StageTaskMessage message) {
}

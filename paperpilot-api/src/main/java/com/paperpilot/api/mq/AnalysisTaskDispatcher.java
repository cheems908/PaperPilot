package com.paperpilot.api.mq;

import com.paperpilot.api.domain.entity.AnalysisTask;
import com.paperpilot.api.domain.entity.StageExecution;
import com.paperpilot.api.domain.enums.StageExecutionStatus;
import com.paperpilot.api.dto.mq.StageTaskMessage;
import com.paperpilot.api.mapper.AnalysisTaskMapper;
import com.paperpilot.api.service.StageExecutionService;
import lombok.RequiredArgsConstructor;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.util.Comparator;
import java.util.List;

/**
 * 首阶段派发器：事务提交后读取已提交的任务与阶段数据，构造 {@link StageTaskMessage}
 * 并交给 {@link StageMessageProducer} 发送.
 *
 * <p>新任务的四个初始阶段均为 PENDING，按执行顺序取最早一个（即 PARSE_PAPER）。
 * 发送失败只记录标识信息（不输出 payload），任务保持 QUEUED 不标记成功；
 * 已知一致性窗口（DB 已提交而进程在 send 前崩溃）由 T7 Outbox/恢复扫描兜底。
 */
@Component
@RequiredArgsConstructor
public class AnalysisTaskDispatcher {

    private static final Logger log = LoggerFactory.getLogger(AnalysisTaskDispatcher.class);

    private final AnalysisTaskMapper analysisTaskMapper;
    private final StageExecutionService stageExecutionService;
    private final StageMessageProducer stageMessageProducer;

    /** 派发任务的首个 PENDING 阶段；无 PENDING 阶段或任务不存在则跳过。 */
    public void dispatchFirstStage(Long taskId) {
        AnalysisTask task = analysisTaskMapper.selectById(taskId);
        if (task == null) {
            log.warn("派发首阶段跳过：任务 {} 不存在", taskId);
            return;
        }
        List<StageExecution> stages = stageExecutionService.listByTask(taskId);
        StageExecution first = stages.stream()
                .filter(s -> s.getStatus() == StageExecutionStatus.PENDING)
                .min(Comparator.comparingInt(s -> s.getStage().ordinal()))
                .orElse(null);
        if (first == null) {
            log.debug("派发首阶段跳过：任务 {} 无 PENDING 阶段", taskId);
            return;
        }

        StageTaskMessage message = StageTaskMessage.create(
                task.getId(), first.getId(), first.getStage(), first.getAttempt());
        try {
            stageMessageProducer.send(message);
        } catch (Exception e) {
            // 只记录标识信息，不输出大 payload；任务保持 QUEUED，不标记成功。
            log.error("首阶段消息发送失败 taskId={} stageExecutionId={} messageId={} requestId={}",
                    message.taskId(), message.stageExecutionId(),
                    message.messageId(), message.requestId(), e);
        }
    }
}

package com.paperpilot.api.mq;

import com.paperpilot.api.dto.mq.StageTaskMessage;
import com.paperpilot.api.service.StageExecutionResult;
import com.paperpilot.api.service.StageOrchestrator;
import com.paperpilot.api.service.StageProgressionService;
import lombok.RequiredArgsConstructor;
import org.apache.rocketmq.spring.annotation.RocketMQMessageListener;
import org.apache.rocketmq.spring.core.RocketMQListener;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

/**
 * 阶段消息消费者：反序列化校验 → 调用幂等编排器 → 成功后推进下一阶段.
 *
 * <p>非法消息（JSON 解析/契约校验失败）直接 ACK 丢弃，避免无限重投；
 * 编排器返回 failed/skipped 均 ACK；可重试失败由 MySQL 到期扫描器生成新 attempt，
 * 不依赖 RocketMQ 对当前消息重投。
 */
@Component
@RocketMQMessageListener(
        topic = "${paperpilot.mq.task-topic}",
        consumerGroup = "${paperpilot.mq.consumer-group}")
@RequiredArgsConstructor
public class StageMessageConsumer implements RocketMQListener<String> {

    private static final Logger log = LoggerFactory.getLogger(StageMessageConsumer.class);

    private final StageOrchestrator orchestrator;
    private final StageProgressionService progressionService;

    @Override
    public void onMessage(String payload) {
        StageTaskMessage message;
        try {
            message = StageTaskMessage.fromJson(payload);
        } catch (Exception e) {
            log.warn("消费非法消息（ACK 丢弃，避免无限重投）: {}", e.getMessage());
            return;
        }
        StageExecutionResult result = orchestrator.orchestrate(message);
        if (result.success()) {
            progressionService.advance(message);
        } else if (!result.skipped()) {
            log.info("阶段执行失败 stageExecutionId={} detail={}",
                    result.stageExecutionId(), result.detail());
        }
        // skipped/failed 均 ACK；自动重试由 RetryScheduler 生成新 attempt 消息
    }
}

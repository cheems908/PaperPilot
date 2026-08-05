package com.paperpilot.api.mq;

import com.paperpilot.api.dto.mq.StageTaskMessage;
import org.apache.rocketmq.spring.core.RocketMQTemplate;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.stereotype.Component;

/**
 * 单阶段消息生产者：把 {@link StageTaskMessage} 以 JSON 串同步发送到配置 topic.
 *
 * <p>只在 RocketMQTemplate 存在时可用（生产环境）；无 MQ 的测试/降级环境
 * {@code getIfAvailable()} 返回 {@code null}，发送即抛 {@link StageDispatchException}，
 * 由 {@link AnalysisTaskDispatcher} 记录标识后吞掉，任务不被标记成功。
 */
@Component
public class StageMessageProducer {

    private static final long SEND_TIMEOUT_MS = 3000L;

    private final ObjectProvider<RocketMQTemplate> rocketMQTemplateProvider;
    private final TaskMqProperties mqProperties;

    public StageMessageProducer(ObjectProvider<RocketMQTemplate> rocketMQTemplateProvider,
                                TaskMqProperties mqProperties) {
        this.rocketMQTemplateProvider = rocketMQTemplateProvider;
        this.mqProperties = mqProperties;
    }

    /** 同步发送阶段消息；失败抛 {@link StageDispatchException}。 */
    public void send(StageTaskMessage message) {
        RocketMQTemplate template = rocketMQTemplateProvider.getIfAvailable();
        if (template == null) {
            throw new StageDispatchException("RocketMQTemplate 未配置，无法发送阶段消息");
        }
        try {
            template.syncSend(mqProperties.taskTopic(), StageTaskMessage.toJson(message), SEND_TIMEOUT_MS);
        } catch (Exception e) {
            throw new StageDispatchException("MQ 发送阶段消息失败: " + e.getMessage(), e);
        }
    }
}

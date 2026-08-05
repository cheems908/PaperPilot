package com.paperpilot.api.mq;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * MQ 配置（{@code paperpilot.mq.*}），由环境变量覆盖（见 {@code application.yml}），
 * 默认值集中引用 {@link TaskMqConstants}；无硬编码密钥。
 *
 * @param taskTopic     阶段任务 topic
 * @param consumerGroup 阶段编排消费者组
 */
@ConfigurationProperties(prefix = TaskMqConstants.PROPERTY_PREFIX)
public record TaskMqProperties(String taskTopic, String consumerGroup) {

    public TaskMqProperties {
        taskTopic = blankToDefault(taskTopic, TaskMqConstants.TASK_TOPIC);
        consumerGroup = blankToDefault(consumerGroup, TaskMqConstants.TASK_CONSUMER_GROUP);
    }

    private static String blankToDefault(String value, String fallback) {
        return (value == null || value.isBlank()) ? fallback : value;
    }
}

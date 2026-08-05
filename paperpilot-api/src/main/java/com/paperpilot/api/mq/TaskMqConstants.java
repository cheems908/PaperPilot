package com.paperpilot.api.mq;

/**
 * MQ topic / consumer-group 与配置键集中常量.
 *
 * <p>代码引用一律走本类常量，不得散落字符串字面量；
 * 默认值与 {@link TaskMqProperties} / {@code application.yml} 中的兜底保持一致。
 */
public final class TaskMqConstants {

    /** 配置前缀：{@code paperpilot.mq.*} */
    public static final String PROPERTY_PREFIX = "paperpilot.mq";

    /** topic 配置键 */
    public static final String TASK_TOPIC_KEY = PROPERTY_PREFIX + ".task-topic";

    /** consumer-group 配置键 */
    public static final String CONSUMER_GROUP_KEY = PROPERTY_PREFIX + ".consumer-group";

    /** 阶段任务默认 topic */
    public static final String TASK_TOPIC = "paperpilot-analysis-stage";

    /** 阶段编排消费者默认组 */
    public static final String TASK_CONSUMER_GROUP = "paperpilot-stage-orchestrator";

    private TaskMqConstants() {
    }
}

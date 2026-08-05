package com.paperpilot.api;

import com.paperpilot.api.mq.TaskMqConstants;
import com.paperpilot.api.mq.TaskMqProperties;
import org.junit.jupiter.api.Test;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * MQ 配置绑定：默认值取集中常量；属性（等价于环境变量覆盖）生效；空值回退默认.
 */
class TaskMqPropertiesTest {

    private final ApplicationContextRunner context = new ApplicationContextRunner()
            .withUserConfiguration(PropsConfig.class);

    @Test
    void defaultsToCentralizedConstants() {
        context.run(ctx -> {
            TaskMqProperties props = ctx.getBean(TaskMqProperties.class);
            assertThat(props.taskTopic()).isEqualTo(TaskMqConstants.TASK_TOPIC);
            assertThat(props.consumerGroup()).isEqualTo(TaskMqConstants.TASK_CONSUMER_GROUP);
        });
    }

    @Test
    void overriddenByPropertiesLikeEnvVars() {
        context.withPropertyValues(
                        "paperpilot.mq.task-topic=stage-override",
                        "paperpilot.mq.consumer-group=group-override")
                .run(ctx -> {
                    TaskMqProperties props = ctx.getBean(TaskMqProperties.class);
                    assertThat(props.taskTopic()).isEqualTo("stage-override");
                    assertThat(props.consumerGroup()).isEqualTo("group-override");
                });
    }

    @Test
    void blankValuesFallBackToConstants() {
        context.withPropertyValues(
                        "paperpilot.mq.task-topic=",
                        "paperpilot.mq.consumer-group= ")
                .run(ctx -> {
                    TaskMqProperties props = ctx.getBean(TaskMqProperties.class);
                    assertThat(props.taskTopic()).isEqualTo(TaskMqConstants.TASK_TOPIC);
                    assertThat(props.consumerGroup()).isEqualTo(TaskMqConstants.TASK_CONSUMER_GROUP);
                });
    }

    @EnableConfigurationProperties(TaskMqProperties.class)
    static class PropsConfig {
    }
}

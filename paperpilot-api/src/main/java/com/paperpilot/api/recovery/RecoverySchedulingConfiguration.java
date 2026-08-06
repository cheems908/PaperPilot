package com.paperpilot.api.recovery;

import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.annotation.EnableScheduling;

/** 开启任务恢复扫描；测试环境可关闭后台扫描。 */
@Configuration
@EnableScheduling
@EnableConfigurationProperties(RecoveryProperties.class)
@ConditionalOnProperty(prefix = "paperpilot.recovery", name = "scheduling-enabled",
        havingValue = "true", matchIfMissing = true)
public class RecoverySchedulingConfiguration {
}

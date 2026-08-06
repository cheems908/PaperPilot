package com.paperpilot.api.retry;

import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.annotation.EnableScheduling;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;

/** 启用基于 MySQL 的到期重试扫描。 */
@Configuration
@EnableScheduling
@ConditionalOnProperty(prefix = "paperpilot.retry", name = "scheduling-enabled",
        havingValue = "true", matchIfMissing = true)
public class RetrySchedulingConfiguration {
}

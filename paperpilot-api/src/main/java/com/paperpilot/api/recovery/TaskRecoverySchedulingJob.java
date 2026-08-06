package com.paperpilot.api.recovery;

import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/** 仅负责按配置触发恢复服务，便于测试显式调用且能真正关闭后台扫描。 */
@Component
@ConditionalOnProperty(prefix = "paperpilot.recovery", name = "scheduling-enabled",
        havingValue = "true", matchIfMissing = true)
public class TaskRecoverySchedulingJob {

    private final TaskRecoveryScheduler scheduler;

    public TaskRecoverySchedulingJob(TaskRecoveryScheduler scheduler) {
        this.scheduler = scheduler;
    }

    @Scheduled(fixedDelayString = "${paperpilot.recovery.scan-interval:PT15S}")
    public void run() {
        scheduler.recover();
    }
}

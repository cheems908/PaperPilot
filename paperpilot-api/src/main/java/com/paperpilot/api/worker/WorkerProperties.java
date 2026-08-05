package com.paperpilot.api.worker;

import com.paperpilot.api.domain.enums.TaskStage;
import org.springframework.boot.context.properties.ConfigurationProperties;

import java.time.Duration;
import java.util.Map;

/**
 * Worker 客户端配置（{@code paperpilot.worker.*}），由环境变量覆盖.
 *
 * <p>连接超时统一（默认约 3s）；读取超时支持按阶段覆盖
 * （{@code stageReadTimeouts}），未配置的阶段用默认读取超时。
 */
@ConfigurationProperties(prefix = "paperpilot.worker")
public record WorkerProperties(
        String baseUrl,
        Duration connectTimeout,
        Duration readTimeout,
        Map<TaskStage, Duration> stageReadTimeouts) {

    public WorkerProperties {
        baseUrl = (baseUrl == null || baseUrl.isBlank()) ? "http://localhost:8001" : baseUrl;
        connectTimeout = (connectTimeout == null) ? Duration.ofSeconds(3) : connectTimeout;
        readTimeout = (readTimeout == null) ? Duration.ofSeconds(60) : readTimeout;
        stageReadTimeouts = (stageReadTimeouts == null) ? Map.of() : stageReadTimeouts;
    }

    /** 阶段读取超时：按阶段覆盖，否则用默认值。 */
    public Duration readTimeoutFor(TaskStage stage) {
        Duration override = stageReadTimeouts.get(stage);
        return override != null ? override : readTimeout;
    }
}

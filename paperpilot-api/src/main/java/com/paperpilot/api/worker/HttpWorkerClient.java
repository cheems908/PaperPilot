package com.paperpilot.api.worker;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import com.paperpilot.api.domain.enums.TaskStage;
import com.paperpilot.api.dto.worker.WorkerErrorResponse;
import com.paperpilot.api.dto.worker.WorkerStageRequest;
import com.paperpilot.api.dto.worker.WorkerStageResponse;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.ResponseEntity;
import org.springframework.http.client.JdkClientHttpRequestFactory;
import org.springframework.stereotype.Component;
import org.springframework.web.client.HttpClientErrorException;
import org.springframework.web.client.HttpServerErrorException;
import org.springframework.web.client.ResourceAccessException;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientException;
import org.springframework.web.client.RestClientResponseException;

import java.net.SocketTimeoutException;
import java.net.http.HttpClient;
import java.net.http.HttpTimeoutException;
import java.nio.charset.StandardCharsets;
import java.util.EnumMap;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.TimeoutException;

/**
 * 基于 Spring RestClient 的 Worker 客户端.
 *
 * <p>按 {@link TaskStage} 建立独立 RestClient（每阶段读取超时不同，连接超时统一约 3s）；
 * 响应以原始字节读取以做最大响应体校验，再解析为 {@link WorkerStageResponse}。
 * 失败统一映射为 {@link WorkerException}（稳定错误码 + retryable 标记）；
 * 日志只记录 endpoint/耗时/状态码/体积摘要，不记录论文全文或输出内容。
 * 本客户端不修改 task/stage 状态，不实现重试/熔断（见 {@link WorkerClient}）。
 */
@Component
public class HttpWorkerClient implements WorkerClient {

    private static final Logger log = LoggerFactory.getLogger(HttpWorkerClient.class);

    /** 最大响应体字节数：超限视为非法响应（防止内存放大）。 */
    static final int MAX_RESPONSE_BYTES = 5 * 1024 * 1024;

    private static final ObjectMapper MAPPER = new ObjectMapper()
            .registerModule(new JavaTimeModule())
            .disable(SerializationFeature.WRITE_DATES_AS_TIMESTAMPS)
            .disable(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES);

    private final WorkerProperties properties;
    private final HttpClient httpClient;
    private final Map<TaskStage, RestClient> clientsByStage = new EnumMap<>(TaskStage.class);

    public HttpWorkerClient(WorkerProperties properties) {
        this.properties = properties;
        this.httpClient = HttpClient.newBuilder()
                .connectTimeout(properties.connectTimeout())
                .build();
        for (TaskStage stage : TaskStage.values()) {
            clientsByStage.put(stage, buildRestClient(stage));
        }
    }

    @Override
    public WorkerStageResponse execute(WorkerStageRequest request) {
        TaskStage stage = request.stage();
        String path = endpointFor(stage);
        long start = System.nanoTime();
        try {
            ResponseEntity<byte[]> response = clientsByStage.get(stage)
                    .post().uri(path).body(request).retrieve().toEntity(byte[].class);
            int status = response.getStatusCode().value();
            byte[] body = response.getBody() != null ? response.getBody() : new byte[0];
            if (body.length > MAX_RESPONSE_BYTES) {
                throw new WorkerException(WorkerErrorCode.INVALID_RESPONSE, status,
                        "响应体过大: " + body.length + " 字节 > " + MAX_RESPONSE_BYTES);
            }
            WorkerStageResponse parsed = parse(body, status);
            if (Boolean.FALSE.equals(parsed.success())) {
                throw new WorkerException(WorkerErrorCode.BUSINESS_FAILED, status,
                        "worker 返回业务失败 stage=" + stage);
            }
            log.info("worker 阶段完成 stage={} endpoint={} status={} durationMs={} outputBytes={}",
                    stage, path, status, elapsedMs(start), body.length);
            return parsed;
        } catch (WorkerException e) {
            log.warn("worker 调用失败 stage={} endpoint={} durationMs={} error={}",
                    stage, path, elapsedMs(start), e.getErrorCode());
            throw e;
        } catch (HttpClientErrorException e) {
            throw errorFrom(WorkerErrorCode.HTTP_4XX, e.getStatusCode().value(), e);
        } catch (HttpServerErrorException e) {
            throw errorFrom(WorkerErrorCode.HTTP_5XX, e.getStatusCode().value(), e);
        } catch (ResourceAccessException e) {
            if (isTimeout(e)) {
                throw new WorkerException(WorkerErrorCode.TIMEOUT, 0, "worker 超时", e);
            }
            throw new WorkerException(WorkerErrorCode.CONNECTION_ERROR, 0, "worker 连接失败", e);
        } catch (RestClientException e) {
            throw new WorkerException(WorkerErrorCode.CONNECTION_ERROR, 0, "worker 调用失败", e);
        }
    }

    private WorkerStageResponse parse(byte[] body, int status) {
        String json = new String(body, StandardCharsets.UTF_8);
        try {
            return MAPPER.readValue(json, WorkerStageResponse.class);
        } catch (JsonProcessingException e) {
            throw new WorkerException(WorkerErrorCode.INVALID_RESPONSE, status,
                    "非法 JSON 响应: " + e.getMessage(), e);
        }
    }

    /** 4xx/5xx：解析远端统一错误体，把 errorCode/retryable 透传到 {@link WorkerException}。 */
    private WorkerException errorFrom(WorkerErrorCode code, int status, RestClientResponseException e) {
        WorkerErrorResponse err = parseErrorBody(e.getResponseBodyAsByteArray());
        if (err != null && err.errorCode() != null) {
            boolean retryable = err.retryable() != null ? err.retryable() : code.isRetryable();
            String message = err.message() != null ? err.message() : "worker error " + status;
            return new WorkerException(code, status, err.errorCode(), retryable, message, e);
        }
        return new WorkerException(code, status, "worker " + status + ": " + e.getStatusCode(), e);
    }

    private WorkerErrorResponse parseErrorBody(byte[] body) {
        if (body == null || body.length == 0) {
            return null;
        }
        try {
            return MAPPER.readValue(body, WorkerErrorResponse.class);
        } catch (Exception e) {
            return null; // 错误体非统一 JSON：忽略，回退到状态码分类
        }
    }

    private RestClient buildRestClient(TaskStage stage) {
        // 连接超时由 HttpClient 统一配置；读取超时按阶段设置（作用于直到响应头的请求预算）
        JdkClientHttpRequestFactory factory = new JdkClientHttpRequestFactory(httpClient);
        factory.setReadTimeout(properties.readTimeoutFor(stage));
        return RestClient.builder().baseUrl(properties.baseUrl()).requestFactory(factory).build();
    }

    /** TaskStage → Python 内部接口路径。 */
    static String endpointFor(TaskStage stage) {
        return switch (stage) {
            case PARSE_PAPER -> "/internal/v1/papers/parse";
            case CLONE_REPOSITORY -> "/internal/v1/repositories/clone";
            case INDEX_CODE -> "/internal/v1/repositories/index";
            case MAP_CONCEPTS -> "/internal/v1/mappings/generate";
            case ANALYZE_ENVIRONMENT, GENERATE_REPORT ->
                    throw new IllegalArgumentException("阶段未映射到 worker 接口: " + stage);
        };
    }

    /**
     * 判定是否为超时：cause 链含各类超时异常，或消息含 "timed out"/"timeout"。
     * Java HttpClient / JDK 实现用不同异常类型表达超时，故同时匹配多种。
     */
    private static boolean isTimeout(ResourceAccessException e) {
        if (hasCause(e, TimeoutException.class)
                || hasCause(e, HttpTimeoutException.class)
                || hasCause(e, SocketTimeoutException.class)) {
            return true;
        }
        String msg = e.getMessage() == null ? "" : e.getMessage().toLowerCase(Locale.ROOT);
        return msg.contains("timed out") || msg.contains("timeout");
    }

    private static boolean hasCause(Throwable t, Class<? extends Throwable> type) {
        Throwable cursor = t;
        while (cursor != null) {
            if (type.isInstance(cursor)) {
                return true;
            }
            cursor = cursor.getCause();
        }
        return false;
    }

    private static long elapsedMs(long startNanos) {
        return (System.nanoTime() - startNanos) / 1_000_000;
    }
}

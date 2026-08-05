package com.paperpilot.api.worker;

import com.paperpilot.api.domain.enums.TaskStage;
import com.paperpilot.api.dto.worker.WorkerStageRequest;
import com.paperpilot.api.dto.worker.WorkerStageResponse;
import okhttp3.mockwebserver.MockResponse;
import okhttp3.mockwebserver.MockWebServer;
import okhttp3.mockwebserver.RecordedRequest;
import okhttp3.mockwebserver.SocketPolicy;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import java.net.ServerSocket;
import java.time.Duration;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Worker 客户端测试（MockWebServer）：四阶段路径映射、成功解析、requestId/attempt
 * 原样传播、超时、连接失败、4xx/5xx、非法 JSON、过大响应、缺失 output、业务失败.
 */
class WorkerClientTest {

    MockWebServer server;

    @AfterEach
    void tearDown() throws Exception {
        if (server != null) {
            server.shutdown();
        }
    }

    // ── 四阶段路径映射 ────────────────────────────────────────────────────

    @Test
    void mapsFourStagesToEndpoints() {
        assertThat(HttpWorkerClient.endpointFor(TaskStage.PARSE_PAPER)).isEqualTo("/internal/v1/papers/parse");
        assertThat(HttpWorkerClient.endpointFor(TaskStage.CLONE_REPOSITORY)).isEqualTo("/internal/v1/repositories/clone");
        assertThat(HttpWorkerClient.endpointFor(TaskStage.INDEX_CODE)).isEqualTo("/internal/v1/repositories/index");
        assertThat(HttpWorkerClient.endpointFor(TaskStage.MAP_CONCEPTS)).isEqualTo("/internal/v1/mappings/generate");
    }

    @Test
    void unmappedStageIsRejected() {
        assertThatThrownBy(() -> HttpWorkerClient.endpointFor(TaskStage.GENERATE_REPORT))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void dispatchesToMappedEndpoint() throws Exception {
        server = new MockWebServer();
        server.enqueue(okResponse());
        server.start();

        client(server, Duration.ofSeconds(5)).execute(request(TaskStage.MAP_CONCEPTS, "req-1", 2));

        RecordedRequest recorded = server.takeRequest();
        assertThat(recorded.getPath()).isEqualTo("/internal/v1/mappings/generate");
    }

    // ── 成功路径 ──────────────────────────────────────────────────────────

    @Test
    void successfulResponseReturnsParsedData() throws Exception {
        server = new MockWebServer();
        server.enqueue(okResponse());
        server.start();

        WorkerStageResponse resp = client(server, Duration.ofSeconds(5))
                .execute(request(TaskStage.PARSE_PAPER, "req-1", 1));

        assertThat(resp.success()).isTrue();
        assertThat(resp.output()).isEqualTo(Map.of("title", "t"));
        assertThat(resp.metrics()).isEqualTo(Map.of("pages", 5));
        assertThat(resp.workerVersion()).isEqualTo("0.1");
        assertThat(resp.artifacts()).isEmpty();
    }

    // ── requestId / attempt 原样传播 ──────────────────────────────────────

    @Test
    void propagatesRequestIdAndAttemptAsIs() throws Exception {
        server = new MockWebServer();
        server.enqueue(okResponse());
        server.start();

        client(server, Duration.ofSeconds(5))
                .execute(request(TaskStage.CLONE_REPOSITORY, "req-trace-42", 3));

        String body = server.takeRequest().getBody().readUtf8();
        assertThat(body).contains("\"schemaVersion\":1");
        assertThat(body).contains("\"requestId\":\"req-trace-42\"");
        assertThat(body).contains("\"taskId\":7");
        assertThat(body).contains("\"stageExecutionId\":34");
        assertThat(body).contains("\"stage\":\"CLONE_REPOSITORY\"");
        assertThat(body).contains("\"attempt\":3");
        assertThat(body).contains("\"input\":{\"fileId\":1}");
    }

    // ── 超时 / 连接失败 / 4xx / 5xx ───────────────────────────────────────

    @Test
    void timeoutThrowsRetryableWorkerException() throws Exception {
        server = new MockWebServer();
        server.enqueue(new MockResponse().setSocketPolicy(SocketPolicy.NO_RESPONSE));
        server.start();

        assertThatThrownBy(() -> client(server, Duration.ofMillis(500))
                .execute(request(TaskStage.PARSE_PAPER, "req-1", 1)))
                .isInstanceOf(WorkerException.class)
                .satisfies(e -> {
                    WorkerException we = (WorkerException) e;
                    assertThat(we.getErrorCode()).isEqualTo(WorkerErrorCode.TIMEOUT);
                    assertThat(we.isRetryable()).isTrue();
                });
    }

    @Test
    void connectionFailureThrowsClassifiableException() throws Exception {
        HttpWorkerClient client = new HttpWorkerClient(new WorkerProperties(
                "http://127.0.0.1:" + freePort(), Duration.ofMillis(300), Duration.ofMillis(500), Map.of()));

        assertThatThrownBy(() -> client.execute(request(TaskStage.PARSE_PAPER, "req-1", 1)))
                .isInstanceOf(WorkerException.class)
                .satisfies(e -> {
                    WorkerException we = (WorkerException) e;
                    assertThat(we.getErrorCode()).isEqualTo(WorkerErrorCode.CONNECTION_ERROR);
                    assertThat(we.isRetryable()).isTrue();
                });
    }

    @Test
    void http4xxMapsToNonRetryable() throws Exception {
        server = new MockWebServer();
        server.enqueue(new MockResponse().setResponseCode(400).setBody("bad request"));
        server.start();

        assertThatThrownBy(() -> client(server, Duration.ofSeconds(5))
                .execute(request(TaskStage.PARSE_PAPER, "req-1", 1)))
                .isInstanceOf(WorkerException.class)
                .satisfies(e -> {
                    WorkerException we = (WorkerException) e;
                    assertThat(we.getErrorCode()).isEqualTo(WorkerErrorCode.HTTP_4XX);
                    assertThat(we.isRetryable()).isFalse();
                });
    }

    @Test
    void http5xxMapsToRetryable() throws Exception {
        server = new MockWebServer();
        server.enqueue(new MockResponse().setResponseCode(500).setBody("boom"));
        server.start();

        assertThatThrownBy(() -> client(server, Duration.ofSeconds(5))
                .execute(request(TaskStage.PARSE_PAPER, "req-1", 1)))
                .isInstanceOf(WorkerException.class)
                .satisfies(e -> {
                    WorkerException we = (WorkerException) e;
                    assertThat(we.getErrorCode()).isEqualTo(WorkerErrorCode.HTTP_5XX);
                    assertThat(we.isRetryable()).isTrue();
                });
    }

    @Test
    void http5xxPreservesRemoteErrorCodeAndRetryable() throws Exception {
        server = new MockWebServer();
        server.enqueue(new MockResponse().setResponseCode(500)
                .setHeader("Content-Type", "application/json")
                .setBody("{\"schemaVersion\":1,\"success\":false,\"errorCode\":\"INVALID_PDF\","
                        + "\"retryable\":false,\"message\":\"not a valid pdf\"}"));
        server.start();

        assertThatThrownBy(() -> client(server, Duration.ofSeconds(5))
                .execute(request(TaskStage.PARSE_PAPER, "req-1", 1)))
                .isInstanceOf(WorkerException.class)
                .satisfies(e -> {
                    WorkerException we = (WorkerException) e;
                    assertThat(we.getErrorCode()).isEqualTo(WorkerErrorCode.HTTP_5XX);
                    assertThat(we.getRemoteErrorCode()).isEqualTo("INVALID_PDF");
                    assertThat(we.isRetryable()).isFalse(); // 远端 retryable 覆盖分类默认
                    assertThat(we.getMessage()).contains("not a valid pdf");
                });
    }

    @Test
    void http4xxFallsBackToStatusCodeWhenBodyIsNotJson() throws Exception {
        server = new MockWebServer();
        server.enqueue(new MockResponse().setResponseCode(400).setBody("<html>bad request</html>"));
        server.start();

        assertThatThrownBy(() -> client(server, Duration.ofSeconds(5))
                .execute(request(TaskStage.PARSE_PAPER, "req-1", 1)))
                .isInstanceOf(WorkerException.class)
                .satisfies(e -> {
                    WorkerException we = (WorkerException) e;
                    assertThat(we.getErrorCode()).isEqualTo(WorkerErrorCode.HTTP_4XX);
                    assertThat(we.getRemoteErrorCode()).isNull();
                    assertThat(we.isRetryable()).isFalse();
                });
    }

    // ── 非法响应 / 过大 / 缺失 output / 业务失败 ──────────────────────────

    @Test
    void invalidJsonResponseRejected() throws Exception {
        server = new MockWebServer();
        server.enqueue(new MockResponse().setResponseCode(200)
                .setHeader("Content-Type", "application/json")
                .setBody("{not json"));
        server.start();

        assertThatThrownBy(() -> client(server, Duration.ofSeconds(5))
                .execute(request(TaskStage.PARSE_PAPER, "req-1", 1)))
                .isInstanceOf(WorkerException.class)
                .satisfies(e -> {
                    WorkerException we = (WorkerException) e;
                    assertThat(we.getErrorCode()).isEqualTo(WorkerErrorCode.INVALID_RESPONSE);
                    assertThat(we.isRetryable()).isFalse();
                });
    }

    @Test
    void oversizedResponseRejected() throws Exception {
        server = new MockWebServer();
        String big = "{\"schemaVersion\":1,\"success\":true,\"output\":\""
                + "x".repeat(6 * 1024 * 1024) + "\"}";
        server.enqueue(new MockResponse().setResponseCode(200).setBody(big));
        server.start();

        assertThatThrownBy(() -> client(server, Duration.ofSeconds(5))
                .execute(request(TaskStage.PARSE_PAPER, "req-1", 1)))
                .isInstanceOf(WorkerException.class)
                .satisfies(e -> {
                    WorkerException we = (WorkerException) e;
                    assertThat(we.getErrorCode()).isEqualTo(WorkerErrorCode.INVALID_RESPONSE);
                    assertThat(we.getMessage()).contains("过大");
                });
    }

    @Test
    void successWithoutOutputRejected() throws Exception {
        server = new MockWebServer();
        server.enqueue(new MockResponse().setResponseCode(200)
                .setHeader("Content-Type", "application/json")
                .setBody("{\"schemaVersion\":1,\"success\":true}"));
        server.start();

        assertThatThrownBy(() -> client(server, Duration.ofSeconds(5))
                .execute(request(TaskStage.PARSE_PAPER, "req-1", 1)))
                .isInstanceOf(WorkerException.class)
                .satisfies(e -> {
                    WorkerException we = (WorkerException) e;
                    assertThat(we.getErrorCode()).isEqualTo(WorkerErrorCode.INVALID_RESPONSE);
                });
    }

    @Test
    void businessFailureMapsToWorkerException() throws Exception {
        server = new MockWebServer();
        server.enqueue(new MockResponse().setResponseCode(200)
                .setHeader("Content-Type", "application/json")
                .setBody("{\"schemaVersion\":1,\"success\":false,\"output\":null}"));
        server.start();

        assertThatThrownBy(() -> client(server, Duration.ofSeconds(5))
                .execute(request(TaskStage.PARSE_PAPER, "req-1", 1)))
                .isInstanceOf(WorkerException.class)
                .satisfies(e -> {
                    WorkerException we = (WorkerException) e;
                    assertThat(we.getErrorCode()).isEqualTo(WorkerErrorCode.BUSINESS_FAILED);
                    assertThat(we.isRetryable()).isFalse();
                });
    }

    // ── 请求构造校验 ──────────────────────────────────────────────────────

    @Test
    void malformedRequestIsRejectedBeforeSending() {
        assertThatThrownBy(() -> new WorkerStageRequest(WorkerStageRequest.SCHEMA_VERSION, "r",
                7L, 34L, TaskStage.PARSE_PAPER, 0, null))
                .isInstanceOf(IllegalArgumentException.class);
    }

    // ── helpers ───────────────────────────────────────────────────────────

    private HttpWorkerClient client(MockWebServer server, Duration readTimeout) {
        return new HttpWorkerClient(new WorkerProperties(
                server.url("/").toString(), Duration.ofMillis(300), readTimeout, Map.of()));
    }

    private WorkerStageRequest request(TaskStage stage, String requestId, int attempt) {
        return new WorkerStageRequest(WorkerStageRequest.SCHEMA_VERSION, requestId, 7L, 34L,
                stage, attempt, Map.of("fileId", 1));
    }

    private MockResponse okResponse() {
        return new MockResponse().setResponseCode(200)
                .setHeader("Content-Type", "application/json")
                .setBody("{\"schemaVersion\":1,\"success\":true,\"output\":{\"title\":\"t\"},"
                        + "\"artifacts\":[],\"metrics\":{\"pages\":5},\"workerVersion\":\"0.1\"}");
    }

    private int freePort() throws Exception {
        try (ServerSocket socket = new ServerSocket(0)) {
            return socket.getLocalPort();
        }
    }
}

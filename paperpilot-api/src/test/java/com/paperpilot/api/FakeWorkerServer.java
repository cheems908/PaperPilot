package com.paperpilot.api;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;

import java.io.IOException;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 假 Worker（Java 测试替身）：提供四个内部阶段接口的固定确定性响应，
 * 记录每个 stageExecutionId 的执行次数，支持 {@code simulate.delayMs} /
 * {@code simulate.failure} 与 {@code unavailable}（503）开关.
 *
 * <p>与 {@code paperpilot-agent/fake_worker.py} 实现同一契约；此处为 Java 集成测试
 * 进程内自包含替身（不依赖 Python 子进程）。
 */
public final class FakeWorkerServer implements AutoCloseable {

    private static final Pattern STAGE_EXECUTION_ID =
            Pattern.compile("\"stageExecutionId\"\\s*:\\s*(\\d+)");

    private final HttpServer server;
    private final ExecutorService executor;
    private final Map<Long, AtomicInteger> executions = new ConcurrentHashMap<>();
    private volatile boolean unavailable = false;

    private FakeWorkerServer(HttpServer server, ExecutorService executor) {
        this.server = server;
        this.executor = executor;
    }

    public static FakeWorkerServer start() {
        try {
            HttpServer server = HttpServer.create(new InetSocketAddress(0), 0);
            ExecutorService executor = Executors.newFixedThreadPool(4, r -> {
                Thread t = new Thread(r, "fake-worker");
                t.setDaemon(true);
                return t;
            });
            FakeWorkerServer fake = new FakeWorkerServer(server, executor);
            for (String path : new String[]{
                    "/internal/v1/papers/parse",
                    "/internal/v1/repositories/clone",
                    "/internal/v1/repositories/index",
                    "/internal/v1/mappings/generate"}) {
                server.createContext(path, fake::handleStage);
            }
            server.createContext("/internal/health", fake::handleHealth);
            server.setExecutor(executor);
            server.start();
            return fake;
        } catch (IOException e) {
            throw new IllegalStateException("假 Worker 启动失败", e);
        }
    }

    public int port() {
        return server.getAddress().getPort();
    }

    public String baseUrl() {
        return "http://localhost:" + port();
    }

    /** 某 stageExecutionId 被实际执行的次数。 */
    public int executionCount(long stageExecutionId) {
        return executions.getOrDefault(stageExecutionId, new AtomicInteger()).get();
    }

    /** 模拟 Worker 不可用：后续请求返回 503。 */
    public void setUnavailable(boolean unavailable) {
        this.unavailable = unavailable;
    }

    @Override
    public void close() {
        server.stop(0);
        executor.shutdownNow();
    }

    private void handleStage(HttpExchange exchange) throws IOException {
        if (unavailable) {
            respond(exchange, 503, "{\"message\":\"worker down\"}");
            return;
        }
        String body = new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8);
        Matcher matcher = STAGE_EXECUTION_ID.matcher(body);
        long stageExecutionId = matcher.find() ? Long.parseLong(matcher.group(1)) : -1L;
        executions.computeIfAbsent(stageExecutionId, k -> new AtomicInteger()).incrementAndGet();

        Map<String, String> query = parseQuery(exchange.getRequestURI().getRawQuery());
        if (query.containsKey("simulate.delayMs")) {
            try {
                Thread.sleep(Long.parseLong(query.get("simulate.delayMs")));
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
        }
        if ("true".equalsIgnoreCase(query.getOrDefault("simulate.failure", "false"))) {
            respond(exchange, 500, "{\"message\":\"simulated failure\"}");
            return;
        }
        exchange.getResponseHeaders().set("Content-Type", "application/json");
        respond(exchange, 200, stageResponse(pathToStage(exchange.getRequestURI().getPath())));
    }

    private void handleHealth(HttpExchange exchange) throws IOException {
        exchange.getResponseHeaders().set("Content-Type", "application/json");
        respond(exchange, 200, "{\"status\":\"ok\",\"service\":\"paperpilot-fake-worker\"}");
    }

    private void respond(HttpExchange exchange, int code, String body) throws IOException {
        byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
        exchange.sendResponseHeaders(code, bytes.length);
        try (OutputStream os = exchange.getResponseBody()) {
            os.write(bytes);
        }
    }

    private static String stageResponse(String stage) {
        if ("INDEX_CODE".equals(stage)) {
            // INDEX 阶段输出必须是合法 IndexResult（含 commitSha），Java 侧幂等 upsert 到 code_symbol
            return "{\"schemaVersion\":1,\"success\":true,"
                    + "\"output\":{\"repo\":\"https://github.com/paperpilot/patchtst\","
                    + "\"commitSha\":\"" + "f".repeat(40) + "\","
                    + "\"files\":[],\"warnings\":[],"
                    + "\"stats\":{\"fileCount\":0,\"symbolCount\":0,\"warningCount\":0}},"
                    + "\"artifacts\":[],\"metrics\":{},\"workerVersion\":\"fake-1.0.0\"}";
        }
        if ("PARSE_PAPER".equals(stage)) {
            return "{\"schemaVersion\":1,\"success\":true,"
                    + "\"output\":{\"paper\":{\"title\":\"PatchTST\",\"sections\":[]},"
                    + "\"parser\":{\"name\":\"fake\",\"version\":\"1\"},\"warnings\":[],\"stats\":{}},"
                    + "\"artifacts\":[],\"metrics\":{},\"workerVersion\":\"fake-1.0.0\"}";
        }
        if ("CLONE_REPOSITORY".equals(stage)) {
            return "{\"schemaVersion\":1,\"success\":true,"
                    + "\"output\":{\"canonicalUrl\":\"https://github.com/paperpilot/patchtst\","
                    + "\"commitSha\":\"" + "f".repeat(40) + "\",\"workspaceRef\":\"task-1/stage-1\"},"
                    + "\"artifacts\":[],\"metrics\":{},\"workerVersion\":\"fake-1.0.0\"}";
        }
        if ("MAP_CONCEPTS".equals(stage)) {
            // MAP 阶段输出必须是合法 MappingOutput（含 concepts + candidates）
            String commit = "f".repeat(40);
            return "{\"schemaVersion\":1,\"success\":true,"
                    + "\"output\":{\"commitSha\":\"" + commit + "\","
                    + "\"concepts\":[{\"conceptId\":\"pc_111111111111111111111111\","
                    + "\"term\":\"channel independence\",\"aliases\":[],\"extractorVersion\":\"compound-rule-v1\","
                    + "\"mentions\":[{\"section\":\"Model\",\"page\":4,\"paragraphId\":\"1.1\","
                    + "\"evidenceText\":\"The model applies channel independence.\"}],"
                    + "\"decision\":\"MAPPED\",\"source\":\"heading\","
                    + "\"evidenceText\":\"The model applies channel independence.\","
                    + "\"candidates\":[{\"symbolRef\":{\"filePath\":\"model.py\","
                    + "\"qualifiedName\":\"PatchTST\",\"name\":\"PatchTST\",\"startLine\":5,"
                    + "\"commitSha\":\"" + commit + "\"},"
                    + "\"semanticScore\":0.1,\"symbolScore\":0,\"keywordScore\":0,"
                    + "\"documentationScore\":1,\"verificationScore\":0.2,\"totalScore\":0.2,"
                    + "\"status\":\"NEEDS_REVIEW\",\"degraded\":false,"
                    + "\"verificationReason\":\"fake\",\"matchedTokens\":[\"channel\",\"independence\"],"
                    + "\"codeEvidence\":\"docstring\"}]}],"
                    + "\"stats\":{\"conceptCount\":1,\"candidateCount\":1,\"needsReviewCount\":1}},"
                    + "\"artifacts\":[],\"metrics\":{},\"workerVersion\":\"fake-1.0.0\"}";
        }
        return "{\"schemaVersion\":1,\"success\":true,"
                + "\"output\":{\"stage\":\"" + stage + "\",\"ok\":true},"
                + "\"artifacts\":[],\"metrics\":{},\"workerVersion\":\"fake-1.0.0\"}";
    }

    private static String pathToStage(String path) {
        return switch (path) {
            case "/internal/v1/papers/parse" -> "PARSE_PAPER";
            case "/internal/v1/repositories/clone" -> "CLONE_REPOSITORY";
            case "/internal/v1/repositories/index" -> "INDEX_CODE";
            case "/internal/v1/mappings/generate" -> "MAP_CONCEPTS";
            default -> path;
        };
    }

    private static Map<String, String> parseQuery(String rawQuery) {
        Map<String, String> map = new java.util.HashMap<>();
        if (rawQuery == null || rawQuery.isBlank()) {
            return map;
        }
        for (String pair : rawQuery.split("&")) {
            int idx = pair.indexOf('=');
            if (idx > 0) {
                map.put(pair.substring(0, idx), pair.substring(idx + 1));
            } else {
                map.put(pair, "");
            }
        }
        return map;
    }
}

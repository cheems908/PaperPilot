package com.paperpilot.api;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.paperpilot.api.common.ApiException;
import com.paperpilot.api.common.ApiResponse;
import com.paperpilot.api.common.ErrorCode;
import com.paperpilot.api.common.GlobalExceptionHandler;
import com.paperpilot.api.common.RequestId;
import com.paperpilot.api.common.RequestIdFilter;
import org.junit.jupiter.api.Test;
import org.slf4j.MDC;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.stream.Collectors;
import java.util.stream.IntStream;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * 统一 requestId 链路单元测试（standalone MockMvc + 过滤器 + 全局异常处理器）：
 * 自动生成、合法值原样传播、非法值拒绝、异常响应携带、MDC 清理与并发不串号.
 */
class RequestIdFilterTest {

    private static final ObjectMapper MAPPER = new ObjectMapper();
    private static final RequestIdFilter FILTER = new RequestIdFilter();

    @RestController
    static class EchoController {
        /** 成功路径：data 回显当前 MDC 的 requestId，便于断言 body/header/MDC 一致。 */
        @GetMapping("/ping")
        ApiResponse<String> ping() {
            return ApiResponse.ok(RequestId.current());
        }

        /** 业务异常路径（409）。 */
        @GetMapping("/boom")
        ApiResponse<Void> boom() {
            throw new ApiException(ErrorCode.CONFLICT, "状态冲突");
        }

        /** 未知异常路径（500）。 */
        @GetMapping("/boom500")
        ApiResponse<Void> boom500() {
            throw new IllegalStateException("unexpected");
        }
    }

    private MockMvc mockMvc() {
        return MockMvcBuilders.standaloneSetup(new EchoController())
                .setControllerAdvice(new GlobalExceptionHandler())
                .addFilter(FILTER)
                .build();
    }

    // ── 验收：未传请求头时自动生成且 body/header 相同 ───────────────────────

    @Test
    void generatesIdWhenHeaderAbsentAndBodyMatchesHeader() throws Exception {
        MvcResult r = mockMvc().perform(get("/ping")).andExpect(status().isOk()).andReturn();
        String header = responseHeader(r);
        assertThat(header).isNotNull().isNotBlank();
        assertThat(RequestId.isValid(header)).isTrue();
        assertThat(bodyField(r, "requestId")).isEqualTo(header);
        assertThat(bodyField(r, "data")).isEqualTo(header); // MDC 与 header 一致
    }

    // ── 验收：合法传入值被原样传播 ─────────────────────────────────────────

    @Test
    void propagatesValidHeaderAsIs() throws Exception {
        String incoming = "req-abc_123.xyz";
        MvcResult r = mockMvc().perform(get("/ping").header(RequestId.HEADER_NAME, incoming))
                .andExpect(status().isOk()).andReturn();
        assertThat(responseHeader(r)).isEqualTo(incoming);
        assertThat(bodyField(r, "requestId")).isEqualTo(incoming);
    }

    // ── 验收：非法值不会进入日志或响应 ─────────────────────────────────────

    @Test
    void rejectsInvalidHeaderValuesAndGeneratesFreshId() throws Exception {
        List<String> invalid = List.of(
                "",                                                     // 空
                "bad\nvalue",                                           // 换行注入
                "bad\rvalue",                                           // 回车
                "a b",                                                  // 空格
                "a\tb",                                                 // 制表
                "x".repeat(RequestId.MAX_LENGTH + 1),                   // 超长
                "中文",                                                 // 非 ASCII 不可打印
                "\u0000x");                                     // 控制字符
        for (String bad : invalid) {
            MvcResult r = mockMvc().perform(get("/ping").header(RequestId.HEADER_NAME, bad))
                    .andExpect(status().isOk()).andReturn();
            String header = responseHeader(r);
            assertThat(header).as("invalid header <%s> must not be echoed", bad)
                    .isNotNull().isNotBlank()
                    .isNotEqualTo(bad);
            assertThat(RequestId.isValid(header)).isTrue();
            assertThat(bodyField(r, "requestId")).isEqualTo(header);
        }
    }

    // ── 验收：异常响应也包含 requestId ────────────────────────────────────

    @Test
    void exceptionResponsesIncludeRequestId() throws Exception {
        // 业务异常（GlobalExceptionHandler 统一转信封）
        MvcResult biz = mockMvc().perform(get("/boom")).andExpect(status().isConflict()).andReturn();
        assertThat(responseHeader(biz)).isNotBlank();
        assertThat(bodyField(biz, "requestId")).isEqualTo(responseHeader(biz));

        // 未知异常（500 兜底）
        MvcResult unknown = mockMvc().perform(get("/boom500"))
                .andExpect(status().isInternalServerError()).andReturn();
        assertThat(responseHeader(unknown)).isNotBlank();
        assertThat(bodyField(unknown, "requestId")).isEqualTo(responseHeader(unknown));
    }

    // ── 验收：前端解包逻辑兼容（新增字段不破坏信封）───────────────────────

    @Test
    void envelopeKeepsFrontendCompatibleShape() throws Exception {
        MvcResult r = mockMvc().perform(get("/ping")).andReturn();
        JsonNode root = MAPPER.readTree(r.getResponse().getContentAsString());
        // 前端 api.js isEnvelope：code 为 number 且 message 存在
        assertThat(root.path("code").isNumber()).isTrue();
        assertThat(root.has("message")).isTrue();
        assertThat(root.has("data")).isTrue();
        assertThat(root.path("requestId").isTextual()).isTrue();
    }

    // ── 验收：请求结束后 MDC 清理 ─────────────────────────────────────────

    @Test
    void mdcIsClearedAfterRequest() throws Exception {
        mockMvc().perform(get("/ping")).andExpect(status().isOk());
        assertThat(MDC.get(RequestId.MDC_KEY)).isNull();
    }

    // ── 验收：并发请求之间 MDC 不串号 ─────────────────────────────────────

    @Test
    void concurrentRequestsDoNotLeakMdc() throws Exception {
        int threads = 8;
        int perThread = 10;
        ExecutorService pool = Executors.newFixedThreadPool(threads);
        CountDownLatch start = new CountDownLatch(1);
        ConcurrentLinkedQueue<String> ids = new ConcurrentLinkedQueue<>();
        ConcurrentLinkedQueue<AssertionError> errors = new ConcurrentLinkedQueue<>();

        List<Future<?>> futures = IntStream.range(0, threads)
                .mapToObj(t -> pool.submit(() -> {
                    MockMvc mvc = mockMvc(); // 每线程独立实例，避免共享状态干扰
                    try {
                        start.await();
                        for (int i = 0; i < perThread; i++) {
                            MvcResult r = mvc.perform(get("/ping")).andExpect(status().isOk()).andReturn();
                            String header = responseHeader(r);
                            ids.add(header);
                            if (!bodyField(r, "requestId").equals(header)
                                    || !bodyField(r, "data").equals(header)) {
                                errors.add(new AssertionError("body/header mismatch: " + header));
                            }
                            if (MDC.get(RequestId.MDC_KEY) != null) {
                                errors.add(new AssertionError(
                                        "MDC leaked after request: " + MDC.get(RequestId.MDC_KEY)));
                            }
                        }
                    } catch (Exception e) {
                        errors.add(new AssertionError("request failed", e));
                    }
                    return null;
                }))
                .collect(Collectors.toList());

        start.countDown();
        pool.shutdown();
        assertThat(pool.awaitTermination(30, TimeUnit.SECONDS)).isTrue();
        assertThat(errors).isEmpty();

        // 全部 requestId 互不相同 → 并发不串号
        List<String> all = ids.stream().distinct().collect(Collectors.toList());
        assertThat(all).hasSize(ids.size());
        assertThat(MDC.get(RequestId.MDC_KEY)).isNull();
    }

    // ── 工具方法 ─────────────────────────────────────────────────────────

    @Test
    void validatesFormatAndLength() {
        assertThat(RequestId.isValid(null)).isFalse();
        assertThat(RequestId.isValid("")).isFalse();
        assertThat(RequestId.isValid("x".repeat(RequestId.MAX_LENGTH))).isTrue();
        assertThat(RequestId.isValid("x".repeat(RequestId.MAX_LENGTH + 1))).isFalse();
        assertThat(RequestId.isValid("550e8400-e29b-41d4-a716-446655440000")).isTrue();
    }

    private String responseHeader(MvcResult r) {
        return r.getResponse().getHeader(RequestId.HEADER_NAME);
    }

    private String bodyField(MvcResult r, String field) throws Exception {
        JsonNode root = MAPPER.readTree(r.getResponse().getContentAsString());
        return root.path(field).asText(null);
    }
}

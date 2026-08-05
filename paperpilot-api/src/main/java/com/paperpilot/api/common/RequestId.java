package com.paperpilot.api.common;

import org.slf4j.MDC;

import java.util.UUID;
import java.util.regex.Pattern;

/**
 * requestId 统一追踪标识：全链路单一来源（HTTP 请求、后续 MQ/Worker DTO）.
 *
 * <p>职责：
 * <ul>
 *   <li>定义响应头与 MDC 键名（{@link #HEADER_NAME} / {@link #MDC_KEY}）；</li>
 *   <li>校验外部传入值 {@link #isValid}：仅接受 1–64 个可打印 ASCII 字符，
 *       拒绝换行、制表、控制字符与非打印字符，避免日志注入与请求头污染；</li>
 *   <li>生成新值 {@link #generate}（UUID）；</li>
 *   <li>读取当前请求的 requestId {@link #current}（来自 MDC，供 ApiResponse /
 *       后续 MQ 与 Worker DTO 直接携带，不重新生成）。</li>
 * </ul>
 *
 * <p>非法传入值不会进入日志或响应：过滤器对非法值直接按“未传”处理，生成新值。
 */
public final class RequestId {

    /** 响应头 / 请求头名称. */
    public static final String HEADER_NAME = "X-Request-ID";

    /** SLF4J MDC 键名（日志模板用 {@code %X{requestId}} 输出）. */
    public static final String MDC_KEY = "requestId";

    /** 最大长度：超出视为非法，避免无限增长. */
    public static final int MAX_LENGTH = 64;

    /** 合法值：1–64 个可打印 ASCII（0x21–0x7E），排除空白与控制字符. */
    private static final Pattern VALID = Pattern.compile("^[!-~]{1," + MAX_LENGTH + "}$");

    private RequestId() {
    }

    /**
     * 校验外部传入的 {@code X-Request-ID}。
     *
     * <p>{@code null}、空串、含空白/换行/控制字符或不可打印字符、超过最大长度均视为非法。
     */
    public static boolean isValid(String value) {
        return value != null && VALID.matcher(value).matches();
    }

    /** 生成新的 requestId（UUID，36 字符，天然满足格式约束）. */
    public static String generate() {
        return UUID.randomUUID().toString();
    }

    /**
     * 读取当前请求上下文的 requestId（来自 MDC）。
     *
     * <p>仅在 HTTP 过滤器链内非 {@code null}；非请求线程（定时任务、SSE 异步推送等）
     * 返回 {@code null}，调用方需自行容忍。
     */
    public static String current() {
        return MDC.get(MDC_KEY);
    }
}

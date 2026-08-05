package com.paperpilot.api.common;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.slf4j.MDC;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;

/**
 * 统一 requestId 请求过滤器.
 *
 * <ol>
 *   <li>优先接受合法的 {@code X-Request-ID}，非法（格式/长度不符，见 {@link RequestId#isValid}）
 *       视为未传，重新生成 UUID；</li>
 *   <li>把 requestId 写入 MDC，并在响应头回写 {@code X-Request-ID}；</li>
 *   <li>请求结束在 {@code finally} 清理 MDC，避免线程池复用污染下一个请求。</li>
 * </ol>
 *
 * <p>响应头在进入过滤器链前即已写入，因此异常路径（全局异常处理器返回错误信封）
 * 同样携带 {@code X-Request-ID}；body 中的 {@code requestId} 由
 * {@link ApiResponse} 从 MDC 读取。
 */
@Component
public class RequestIdFilter extends OncePerRequestFilter {

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response,
                                    FilterChain chain) throws ServletException, IOException {
        String requestId = resolve(request.getHeader(RequestId.HEADER_NAME));
        MDC.put(RequestId.MDC_KEY, requestId);
        response.setHeader(RequestId.HEADER_NAME, requestId);
        try {
            chain.doFilter(request, response);
        } finally {
            MDC.remove(RequestId.MDC_KEY);
        }
    }

    /** 合法值原样传播，否则生成新 UUID。非法值不进入日志或响应。 */
    private String resolve(String header) {
        return RequestId.isValid(header) ? header : RequestId.generate();
    }
}

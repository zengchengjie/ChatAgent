package com.chatagent.observability;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import lombok.extern.slf4j.Slf4j;
import io.micrometer.tracing.Span;
import io.micrometer.tracing.Tracer;
import org.springframework.lang.NonNull;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

/**
 * 为每个 HTTP 请求补齐 traceId，并记录 request_start/request_end，方便线上排查“前端卡住但无日志”的情况。
 *
 * <p>同时把 traceId 写入响应头 X-Trace-Id，便于从浏览器 Network 反查后端日志。
 */
@Component
@Slf4j
public class RequestTraceLoggingFilter extends OncePerRequestFilter {

    public static final String TRACE_ID_HEADER = "X-Trace-Id";

    private final Tracer tracer;

    public RequestTraceLoggingFilter(Tracer tracer) {
        this.tracer = tracer;
    }

    @Override
    protected void doFilterInternal(
            @NonNull HttpServletRequest request,
            @NonNull HttpServletResponse response,
            @NonNull FilterChain filterChain)
            throws ServletException, IOException {
        Span span = tracer.currentSpan();
        String traceId = span != null ? span.context().traceId() : null;
        if (traceId != null && !traceId.isBlank()) {
            response.setHeader(TRACE_ID_HEADER, traceId);
        }

        long t0 = System.currentTimeMillis();
        String method = request.getMethod();
        String uri = request.getRequestURI();
        String q = request.getQueryString();
        String path = q == null ? uri : (uri + "?" + q);
        log.info("event=request_start method={} path={}", method, path);
        try {
            filterChain.doFilter(request, response);
        } finally {
            long ms = System.currentTimeMillis() - t0;
            int status = response.getStatus();
            log.info("event=request_end method={} path={} status={} ms={}", method, path, status, ms);
        }
    }
}


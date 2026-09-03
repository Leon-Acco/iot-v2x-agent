package com.dst.v2xagent.observability.trace;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;
import java.util.List;

/**
 * 追踪过滤器：每个请求生成 / 透传 traceId（X-Trace-Id 响应头回显），请求结束统一落库
 */
@Component
@Order(Ordered.HIGHEST_PRECEDENCE + 10)
public class TraceFilter extends OncePerRequestFilter {

    private final TraceRecorder recorder;

    public TraceFilter(TraceRecorder recorder) {
        this.recorder = recorder;
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain)
            throws ServletException, IOException {
        String traceId = TraceContext.begin(request.getHeader("X-Trace-Id"));
        response.setHeader("X-Trace-Id", traceId);
        try {
            chain.doFilter(request, response);
        } finally {
            List<TraceSpan> spans = TraceContext.spans();
            if (!spans.isEmpty()) {
                recorder.flush(traceId, spans);
            }
            TraceContext.end();
        }
    }
}

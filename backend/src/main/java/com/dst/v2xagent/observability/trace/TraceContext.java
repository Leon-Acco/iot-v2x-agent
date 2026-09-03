package com.dst.v2xagent.observability.trace;

import java.util.List;
import java.util.UUID;
import java.util.concurrent.CopyOnWriteArrayList;

/**
 * 追踪上下文（请求级 ThreadLocal）
 * 入口（TraceFilter）生成 traceId，链路内任何组件可挂 span，请求结束统一落库。
 * 无上下文时所有方法安全空操作（异步线程不会炸）。
 */
public final class TraceContext {

    private static final ThreadLocal<TraceContext> HOLDER = new ThreadLocal<>();

    private final String traceId;
    private final List<TraceSpan> spans = new CopyOnWriteArrayList<>();

    private TraceContext(String traceId) {
        this.traceId = traceId;
    }

    /** 开启追踪：传入外部 traceId（可空），返回最终 traceId */
    public static String begin(String traceId) {
        String id = (traceId == null || traceId.isBlank())
                ? UUID.randomUUID().toString().replace("-", "").substring(0, 16)
                : traceId;
        HOLDER.set(new TraceContext(id));
        return id;
    }

    /** 当前 traceId（无上下文返回 null） */
    public static String currentTraceId() {
        TraceContext ctx = HOLDER.get();
        return ctx == null ? null : ctx.traceId;
    }

    /** 挂载一个已完成的 span */
    public static void addSpan(TraceSpan span) {
        TraceContext ctx = HOLDER.get();
        if (ctx != null) {
            ctx.spans.add(span);
        }
    }

    /** 当前所有 span（不可变副本） */
    public static List<TraceSpan> spans() {
        TraceContext ctx = HOLDER.get();
        return ctx == null ? List.of() : List.copyOf(ctx.spans);
    }

    /** 结束追踪并清理上下文 */
    public static void end() {
        HOLDER.remove();
    }

    /** 开启一个计时 span：配合 try-with-resources 自动关闭 */
    public static Span span(String type, String name) {
        return new Span(type, name);
    }

    /** span 计时句柄：close 时自动记录耗时 */
    public static final class Span implements AutoCloseable {
        private final String type;
        private final String name;
        private final long start = System.currentTimeMillis();
        private String status = "ok";
        private String detail;

        private Span(String type, String name) {
            this.type = type;
            this.name = name;
        }

        public Span detail(String d) {
            this.detail = d;
            return this;
        }

        public Span fail(String d) {
            this.status = "error";
            this.detail = d;
            return this;
        }

        @Override
        public void close() {
            TraceContext.addSpan(new TraceSpan(type, name, start, System.currentTimeMillis() - start, status, detail));
        }
    }
}

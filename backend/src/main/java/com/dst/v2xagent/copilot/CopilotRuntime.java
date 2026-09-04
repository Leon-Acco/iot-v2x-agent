package com.dst.v2xagent.copilot;

import com.dst.v2xagent.capability.RenderChartTool;
import com.dst.v2xagent.llm.LlmProperties;
import com.dst.v2xagent.memory.MemoryExtractor;
import com.dst.v2xagent.memory.SessionStore;
import com.dst.v2xagent.runtime.RunAuditRepository;
import com.dst.v2xagent.runtime.spi.StreamingModelClient;
import com.dst.v2xagent.runtime.spi.StructuredModelClient;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

/**
 * 多 Agent 编排层共享依赖集合（减少各类构造函数参数量）。
 * 注意：本层只读 Doris 模拟库 dst_v2x_sim，与旧链路（真实库）完全隔离。
 */
@Component
@org.springframework.boot.autoconfigure.condition.ConditionalOnExpression("'${llm.active:mock}' != 'mock'")
public class CopilotRuntime {

    private final LlmProperties llmProperties;
    private final StructuredModelClient structuredModelClient;
    private final StreamingModelClient streamingModelClient;
    private final JdbcTemplate analyticsJdbcTemplate;
    private final SessionStore sessionStore;
    private final RunAuditRepository auditRepository;
    private final RenderChartTool renderChartTool;
    private final MemoryExtractor memoryExtractor;
    private final com.dst.v2xagent.observability.trace.TraceRecorder traceRecorder;
    /** 任务卡 2.0（save_task 工具依赖；ObjectProvider 可空防循环） */
    private final org.springframework.beans.factory.ObjectProvider<com.dst.v2xagent.task.TaskService> taskServiceProvider;
    private final org.springframework.beans.factory.ObjectProvider<com.dst.v2xagent.task.TaskSchedulerHolder> taskSchedulerProvider;

    /** 显式构造器注入（双数据源必须 @Qualifier，不用 Lombok） */
    public CopilotRuntime(LlmProperties llmProperties,
                          StructuredModelClient structuredModelClient,
                          StreamingModelClient streamingModelClient,
                          @Qualifier("analyticsJdbcTemplate") JdbcTemplate analyticsJdbcTemplate,
                          SessionStore sessionStore,
                          RunAuditRepository auditRepository,
                          RenderChartTool renderChartTool,
                          MemoryExtractor memoryExtractor,
                          com.dst.v2xagent.observability.trace.TraceRecorder traceRecorder,
                          org.springframework.beans.factory.ObjectProvider<com.dst.v2xagent.task.TaskService> taskServiceProvider,
                          org.springframework.beans.factory.ObjectProvider<com.dst.v2xagent.task.TaskSchedulerHolder> taskSchedulerProvider) {
        this.llmProperties = llmProperties;
        this.structuredModelClient = structuredModelClient;
        this.streamingModelClient = streamingModelClient;
        this.analyticsJdbcTemplate = analyticsJdbcTemplate;
        this.sessionStore = sessionStore;
        this.auditRepository = auditRepository;
        this.renderChartTool = renderChartTool;
        this.memoryExtractor = memoryExtractor;
        this.traceRecorder = traceRecorder;
        this.taskServiceProvider = taskServiceProvider;
        this.taskSchedulerProvider = taskSchedulerProvider;
    }

    public LlmProperties llm() { return llmProperties; }

    public StructuredModelClient structured() { return structuredModelClient; }

    public StreamingModelClient streaming() { return streamingModelClient; }

    public JdbcTemplate jdbc() { return analyticsJdbcTemplate; }

    public SessionStore sessions() { return sessionStore; }

    public RunAuditRepository audit() { return auditRepository; }

    public RenderChartTool charts() { return renderChartTool; }

    public MemoryExtractor memoryExtractor() { return memoryExtractor; }

    public com.dst.v2xagent.observability.trace.TraceRecorder traceRecorder() { return traceRecorder; }

    /** 任务服务（save_task 工具用；理论非空，防御式可空） */
    public com.dst.v2xagent.task.TaskService taskService() {
        return taskServiceProvider.getIfAvailable();
    }

    /** 任务调度持有者（save_task 工具注册 cron 用） */
    public com.dst.v2xagent.task.TaskSchedulerHolder taskScheduler() {
        return taskSchedulerProvider.getIfAvailable();
    }
}

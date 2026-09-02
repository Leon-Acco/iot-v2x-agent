package com.dst.v2xagent.agui;

import com.dst.v2xagent.agui.auth.AuthFilter;
import com.dst.v2xagent.capability.CapabilityService;
import com.dst.v2xagent.common.PermissionContext;
import com.dst.v2xagent.runtime.RunOrchestrator;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import java.io.IOException;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * AG-UI 辅助端点（非协议本体）。
 * 协议运行端点 /agui/run 已交由 AgentScope 官方 starter 承载；
 * 本类保留：取消、断线重放、参数微调重跑、反馈入库。
 */
@Slf4j
@RestController
@RequestMapping("/ag-ui")
@RequiredArgsConstructor
public class AgUiController {

    private final RunOrchestrator orchestrator;
    private final CapabilityService capabilityService;
    private final StringRedisTemplate redisTemplate;
    private final JdbcTemplate controlJdbcTemplate;

    private final ExecutorService runExecutor = Executors.newVirtualThreadPerTaskExecutor();

    /** 取消运行 */
    @PostMapping("/run/{runId}/cancel")
    public Map<String, Object> cancel(@PathVariable String runId) {
        orchestrator.cancel(runId);
        return Map.of("success", true);
    }

    /** 断线续传：按 Last-Event-ID 从 Redis 事件缓冲重放 */
    @GetMapping(value = "/run/{runId}/replay", produces = MediaType.TEXT_EVENT_STREAM_VALUE)
    public SseEmitter replay(@PathVariable String runId, HttpServletRequest request) {
        permission(request); // 鉴权
        SseEmitter emitter = new SseEmitter(Duration.ofMinutes(1).toMillis());
        String lastEventId = request.getHeader("Last-Event-ID");
        long from = lastEventId != null ? Long.parseLong(lastEventId) : 0;
        runExecutor.submit(() -> {
            try {
                List<String> events = redisTemplate.opsForList().range("agui:run:" + runId + ":events", 0, -1);
                if (events != null) {
                    for (String json : events) {
                        long id = Long.parseLong(json.substring(json.indexOf("\"id\":") + 5, json.indexOf(",", json.indexOf("\"id\":"))));
                        if (id > from) {
                            emitter.send(SseEmitter.event().id(String.valueOf(id)).data(json));
                        }
                    }
                }
                emitter.complete();
            } catch (IOException e) {
                emitter.completeWithError(e);
            }
        });
        return emitter;
    }

    /** 前端参数微调/换图后重跑：绕过 LLM，直接 capabilityId + params 重放执行器 */
    public record ToolResultRequest(@NotBlank String capabilityId, Map<String, Object> params) {}

    @PostMapping("/tool-result")
    public Map<String, Object> toolResult(@Valid @RequestBody ToolResultRequest req, HttpServletRequest request) {
        PermissionContext ctx = permission(request);
        CapabilityService.InvokeOutcome outcome = capabilityService.invoke(req.capabilityId(),
                req.params() != null ? req.params() : Map.of(), ctx);
        return Map.of(
                "columns", outcome.table().columns(),
                "rows", outcome.table().rows().stream().map(r -> java.util.Arrays.stream(r)
                        .map(c -> c == null ? null : c.toString()).toList()).toList(),
                "rowCount", outcome.table().rowCount(),
                "truncated", outcome.table().truncated(),
                "freshness", Map.of("dataAsOf", outcome.table().freshness().dataAsOf(),
                        "policyType", outcome.table().freshness().policyType(),
                        "expectedDelayMin", outcome.table().freshness().expectedDelayMin()),
                "stats", Map.of("elapsedMs", outcome.table().stats().elapsedMs(),
                        "scannedRows", outcome.table().stats().scannedRows(),
                        "sqlSnapshot", outcome.table().stats().sqlSnapshot()),
                "chart", outcome.chart());
    }

    /** 反馈：与 traceId 绑定入库，直接喂给 Bad Case 工作台 */
    public record FeedbackRequest(@NotBlank String runId, String traceId, int rating, String comment) {}

    @PostMapping("/feedback")
    public Map<String, Object> feedback(@Valid @RequestBody FeedbackRequest req, HttpServletRequest request) {
        PermissionContext ctx = permission(request);
        controlJdbcTemplate.update("""
                INSERT INTO feedback (tenant_id, run_id, trace_id, user_id, rating, comment)
                VALUES (?, ?, ?, ?, ?, ?)
                """, ctx.tenantId(), req.runId(), req.traceId(), ctx.userId(), req.rating(), req.comment());
        return Map.of("success", true);
    }

    private PermissionContext permission(HttpServletRequest request) {
        return (PermissionContext) request.getAttribute(AuthFilter.ATTR_PERMISSION);
    }
}

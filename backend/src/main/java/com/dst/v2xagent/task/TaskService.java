package com.dst.v2xagent.task;

import com.dst.v2xagent.capability.CapabilityRegistry;
import com.dst.v2xagent.capability.CapabilityService;
import com.dst.v2xagent.capability.model.CapabilityDefinition;
import com.dst.v2xagent.capability.model.TableResult;
import com.dst.v2xagent.common.ApiException;
import com.dst.v2xagent.common.PermissionContext;
import com.dst.v2xagent.copilot.CopilotRuntime;
import com.dst.v2xagent.memory.SessionStore;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 任务卡 2.0 业务主体：会话流程精炼为可执行任务（task_definition），
 * 支持一键执行（手动）与定时执行（cron，由 TaskSchedulerHolder 驱动）。
 * 执行链路零 LLM：每步复用 CapabilityService.invoke（解析→防火墙→执行），
 * 相对时间词按执行当天现算窗口，ACL 按触发人当前权限注入——任务永不过期。
 * 执行完成后按 analyze_report 开关用分析模型（LLM）对多步结果生成分析报告。
 */
@Slf4j
@Service
public class TaskService {

    private final CapabilityRegistry registry;
    private final CapabilityService capabilityService;
    private final TaskStepRefiner refiner;
    private final ObjectMapper mapper;
    /** 分析模型运行时（copilot 链路，mock 模式下不存在——ObjectProvider 打破循环依赖且可空） */
    private final ObjectProvider<CopilotRuntime> runtimeProvider;
    /** 会话存储（save_task 跨轮回退读上一轮工具帧用） */
    private final SessionStore sessionStore;

    private final JdbcTemplate controlJdbcTemplate;

    /** 显式注入控制库 JdbcTemplate（双数据源强隔离） */
    public TaskService(CapabilityRegistry registry, CapabilityService capabilityService,
                       TaskStepRefiner refiner, ObjectMapper mapper,
                       ObjectProvider<CopilotRuntime> runtimeProvider,
                       SessionStore sessionStore,
                       @Qualifier("controlJdbcTemplate") JdbcTemplate controlJdbcTemplate) {
        this.registry = registry;
        this.capabilityService = capabilityService;
        this.refiner = refiner;
        this.mapper = mapper;
        this.runtimeProvider = runtimeProvider;
        this.sessionStore = sessionStore;
        this.controlJdbcTemplate = controlJdbcTemplate;
    }

    /** 创建请求：steps 为会话工具帧原文（前端透传），精炼归一由后端完成 */
    public record CreateRequest(String title, List<TaskStepRefiner.Frame> steps,
                                String sourceSessionId, String sourceQuestion,
                                Boolean analyzeReport) {}

    /** 更新请求：仅允许改标题与执行预算 */
    public record UpdateRequest(String title, Integer timeoutSeconds) {}

    /** 调度设置请求：enabled=false 一律传 cronExpr 可空 */
    public record ScheduleRequest(boolean enabled, String cronExpr) {}

    /** 定时连续失败自动熔停阈值 */
    public static final int MAX_CONSECUTIVE_FAILURES = 3;

    // ==================== 创建（精炼） ====================

    /**
     * 从会话工具帧创建任务。全帧不可固化时拒绝；部分帧跳过时在返回值 skipped 中说明。
     */
    public Map<String, Object> createFromSession(PermissionContext ctx, CreateRequest req) {
        TaskStepRefiner.RefineResult refined = refiner.refine(req.steps());
        if (refined.steps().isEmpty()) {
            throw ApiException.paramInvalid("本轮对话没有可固化的查询步骤"
                    + (refined.skipped().isEmpty() ? "" : "（" + refined.skipped().get(0).reason() + "）"));
        }
        String title = req.title() == null || req.title().isBlank()
                ? truncate(req.sourceQuestion(), 128) : truncate(req.title(), 128);
        if (title == null || title.isBlank()) {
            throw ApiException.paramInvalid("任务标题不能为空");
        }
        try {
            int analyze = req.analyzeReport() == null || req.analyzeReport() ? 1 : 0;
            controlJdbcTemplate.update("""
                    INSERT INTO task_definition (tenant_id, title, source_session_id, source_question,
                        steps_json, step_count, analyze_report, owner_username)
                    VALUES (?, ?, ?, ?, ?, ?, ?, ?)
                    """,
                    ctx.tenantId(), title,
                    truncate(req.sourceSessionId(), 64), truncate(req.sourceQuestion(), 512),
                    mapper.writeValueAsString(refined.steps()), refined.steps().size(),
                    analyze, ctx.username());
        } catch (Exception e) {
            throw new IllegalStateException("任务保存失败: " + e.getMessage(), e);
        }
        Long id = controlJdbcTemplate.queryForObject(
                "SELECT id FROM task_definition WHERE tenant_id=? AND owner_username=? ORDER BY id DESC LIMIT 1",
                Long.class, ctx.tenantId(), ctx.username());
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("id", id);
        out.put("title", title);
        out.put("steps", refined.steps());
        out.put("skipped", refined.skipped());
        return out;
    }

    // ==================== 执行（手动/定时统一入口，零 LLM） ====================

    /**
     * 执行一次任务。逐步顺序执行，单步失败不炸整任务（PARTIAL）；
     * 预算耗尽余步 SKIPPED；结果落 task_run 并滚动更新 task_definition 摘要。
     * @param triggerType manual（HTTP 同步）/ cron（调度线程，仅入库）
     * @param ctx         执行权限上下文（手动=操作者，定时=创建者当前权限）
     */
    public Map<String, Object> runOnce(long taskId, String triggerType, PermissionContext ctx) {
        Map<String, Object> task = requireTask(ctx, taskId);
        long start = System.currentTimeMillis();
        int timeoutSeconds = ((Number) task.get("timeout_seconds")).intValue();
        long deadline = start + timeoutSeconds * 1000L;

        List<Map<String, Object>> steps = readSteps(task);
        List<Map<String, Object>> stepResults = new ArrayList<>();
        int okCount = 0;
        int failCount = 0;
        for (Map<String, Object> step : steps) {
            String capabilityId = String.valueOf(step.get("capabilityId"));
            Map<String, Object> stepResult = new LinkedHashMap<>();
            stepResult.put("seq", step.get("seq"));
            stepResult.put("capabilityId", capabilityId);
            stepResult.put("displayName", step.get("displayName"));
            long stepStart = System.currentTimeMillis();
            if (System.currentTimeMillis() > deadline) {
                stepResult.put("status", "SKIPPED");
                stepResult.put("error", "执行预算耗尽");
                stepResults.add(stepResult);
                continue;
            }
            try {
                @SuppressWarnings("unchecked")
                Map<String, Object> params = step.get("params") instanceof Map<?, ?> m
                        ? (Map<String, Object>) m : Map.of();
                CapabilityService.InvokeOutcome outcome =
                        capabilityService.invoke(capabilityId, params, ctx);
                stepResult.put("status", "SUCCESS");
                stepResult.put("chartHint", outcome.definition().getChartHint());
                stepResult.put("result", tablePayload(outcome.table()));
                okCount++;
            } catch (Exception e) {
                stepResult.put("status", "FAILED");
                stepResult.put("error", rootMessage(e));
                failCount++;
            }
            stepResult.put("elapsedMs", System.currentTimeMillis() - stepStart);
            stepResults.add(stepResult);
        }
        String status = failCount == 0 ? "SUCCESS" : (okCount > 0 ? "PARTIAL" : "FAILED");
        long elapsed = System.currentTimeMillis() - start;

        // 落 task_run + 滚动摘要；定时时维护连续失败计数与自动熔停
        boolean autoPaused = false;
        Long runId = null;
        String error = failCount > 0 && okCount == 0
                ? truncate(stepResults.stream().map(s -> String.valueOf(s.get("error")))
                        .filter(e -> e != null && !"null".equals(e)).findFirst().orElse("执行失败"), 1024)
                : null;
        // 分析报告：analyze_report=1 且有成功步骤时，用分析模型对多步结果生成报告（失败不阻塞任务状态）
        String conclusion = null;
        if (analyzeEnabled(task) && okCount > 0) {
            conclusion = generateReport(task, triggerType, stepResults);
            // 异常自动复核：报告尾部标记 <复核:车辆> 时触发 anomaly_explain 深度复核（追加步骤与复核结论段）
            if (conclusion != null) {
                conclusion = autoReviewIfNeeded(task, conclusion, stepResults, ctx);
            }
        }
        elapsed = System.currentTimeMillis() - start; // 报告生成计入总耗时
        try {
            runId = insertRun(task, triggerType, ctx.username(), status, elapsed,
                    ctx.fingerprint(), stepResults, conclusion, error);
            if ("cron".equals(triggerType)) {
                int failures = "FAILED".equals(status)
                        ? ((Number) task.get("consecutive_failures")).intValue() + 1 : 0;
                autoPaused = failures >= MAX_CONSECUTIVE_FAILURES;
                controlJdbcTemplate.update("""
                        UPDATE task_definition SET last_run_id=?, last_fire_at=NOW(3), last_status=?,
                            consecutive_failures=?, status=IF(?, 'PAUSED', status)
                        WHERE id=?
                        """, runId, status, failures, autoPaused, taskId);
            } else {
                controlJdbcTemplate.update("""
                        UPDATE task_definition SET last_run_id=?, last_fire_at=NOW(3), last_status=?
                        WHERE id=?
                        """, runId, status, taskId);
            }
        } catch (Exception e) {
            log.error("任务执行结果落库失败 taskId={}", taskId, e);
        }

        Map<String, Object> out = new LinkedHashMap<>();
        out.put("runId", runId);
        out.put("taskId", taskId);
        out.put("conclusion", conclusion);
        out.put("title", task.get("title"));
        out.put("triggerType", triggerType);
        out.put("triggeredBy", ctx.username());
        out.put("status", status);
        out.put("elapsedMs", elapsed);
        out.put("steps", stepResults);
        out.put("autoPaused", autoPaused);
        return out;
    }

    // ==================== CRUD ====================

    /** 我的任务列表（admin 看全租户），最近 100 条 */
    public List<Map<String, Object>> listMine(PermissionContext ctx) {
        String sql = ctx.isAdmin()
                ? "SELECT id, title, step_count, schedule_type, cron_expr, schedule_enabled, next_fire_at,"
                + " last_status, last_fire_at, status, created_at FROM task_definition"
                + " WHERE tenant_id=? AND status != 'ARCHIVED' ORDER BY id DESC LIMIT 100"
                : "SELECT id, title, step_count, schedule_type, cron_expr, schedule_enabled, next_fire_at,"
                + " last_status, last_fire_at, status, created_at FROM task_definition"
                + " WHERE tenant_id=? AND owner_username=? AND status != 'ARCHIVED' ORDER BY id DESC LIMIT 100";
        return ctx.isAdmin() ? controlJdbcTemplate.queryForList(sql, ctx.tenantId())
                : controlJdbcTemplate.queryForList(sql, ctx.tenantId(), ctx.username());
    }

    /** 任务详情：steps + 最近一次 run 摘要 */
    public Map<String, Object> get(PermissionContext ctx, long id) {
        Map<String, Object> task = requireTask(ctx, id);
        Map<String, Object> out = new LinkedHashMap<>(task);
        out.put("steps", readSteps(task));
        out.remove("steps_json");
        List<Map<String, Object>> lastRun = controlJdbcTemplate.queryForList(
                "SELECT id, trigger_type, triggered_by, status, started_at, elapsed_ms, error_msg"
                        + " FROM task_run WHERE tenant_id=? AND task_id=? ORDER BY id DESC LIMIT 1",
                ctx.tenantId(), id);
        out.put("lastRun", lastRun.isEmpty() ? null : lastRun.get(0));
        return out;
    }

    /** 更新（仅标题与执行预算） */
    public void update(PermissionContext ctx, long id, UpdateRequest req) {
        requireTask(ctx, id);
        if (req.title() != null && !req.title().isBlank()) {
            controlJdbcTemplate.update("UPDATE task_definition SET title=? WHERE id=?",
                    truncate(req.title(), 128), id);
        }
        if (req.timeoutSeconds() != null) {
            if (req.timeoutSeconds() < 10 || req.timeoutSeconds() > 300) {
                throw ApiException.paramInvalid("timeoutSeconds 取值范围 10~300 秒");
            }
            controlJdbcTemplate.update("UPDATE task_definition SET timeout_seconds=? WHERE id=?",
                    req.timeoutSeconds(), id);
        }
    }

    /** 归档（软删除；调度取消由 Controller 编排 holder.cancel） */
    public void delete(PermissionContext ctx, long id) {
        requireTask(ctx, id);
        controlJdbcTemplate.update("UPDATE task_definition SET status='ARCHIVED', schedule_enabled=0 WHERE id=?", id);
    }

    /** 设置/停用定时（DB 部分；注册/取消由 Controller 编排 holder） */
    public Map<String, Object> setSchedule(PermissionContext ctx, long id, ScheduleRequest req) {
        requireTask(ctx, id);
        if (req.enabled()) {
            if (req.cronExpr() == null || req.cronExpr().isBlank()) {
                throw ApiException.paramInvalid("启用定时必须提供 cron 表达式");
            }
            String next = nextFireTimes(req.cronExpr(), 1).get(0);
            controlJdbcTemplate.update("""
                    UPDATE task_definition SET schedule_type='cron', cron_expr=?, schedule_enabled=1,
                        consecutive_failures=0, status='ACTIVE', next_fire_at=?
                    WHERE id=?
                    """, req.cronExpr().trim(), next, id);
        } else {
            controlJdbcTemplate.update(
                    "UPDATE task_definition SET schedule_enabled=0 WHERE id=?", id);
        }
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("success", true);
        if (req.enabled()) {
            out.put("nextFireAt", nextFireTimes(req.cronExpr().trim(), 1).get(0));
        }
        return out;
    }

    // ==================== 执行历史 ====================

    /** 执行历史列表（≤50） */
    public List<Map<String, Object>> listRuns(PermissionContext ctx, long taskId, int limit) {
        requireTask(ctx, taskId);
        int safe = Math.max(1, Math.min(limit <= 0 ? 20 : limit, 50));
        return controlJdbcTemplate.queryForList(
                "SELECT id, trigger_type, triggered_by, status, started_at, finished_at, elapsed_ms, error_msg"
                        + " FROM task_run WHERE tenant_id=? AND task_id=? ORDER BY id DESC LIMIT " + safe,
                ctx.tenantId(), taskId);
    }

    /** 单次执行详情（含每步表格结果） */
    public Map<String, Object> getRun(PermissionContext ctx, long runId) {
        List<Map<String, Object>> rows = controlJdbcTemplate.queryForList(
                "SELECT r.*, d.title, d.owner_username FROM task_run r"
                        + " JOIN task_definition d ON d.id = r.task_id"
                        + " WHERE r.tenant_id=? AND r.id=?", ctx.tenantId(), runId);
        if (rows.isEmpty()) {
            throw ApiException.paramInvalid("执行记录不存在: " + runId);
        }
        Map<String, Object> row = rows.get(0);
        if (!ctx.isAdmin() && !ctx.username().equals(String.valueOf(row.get("triggered_by")))
                && !ctx.username().equals(String.valueOf(row.get("owner_username")))) {
            throw ApiException.scopeDenied("只能查看自己相关的执行记录");
        }
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("runId", runId);
        out.put("taskId", row.get("task_id"));
        out.put("title", row.get("title"));
        out.put("triggerType", row.get("trigger_type"));
        out.put("triggeredBy", row.get("triggered_by"));
        out.put("status", row.get("status"));
        out.put("elapsedMs", row.get("elapsed_ms"));
        out.put("startedAt", row.get("started_at"));
        out.put("conclusion", row.get("conclusion"));
        out.put("error", row.get("error_msg"));
        Object stepsJson = row.get("steps_result_json");
        if (stepsJson != null) {
            try {
                out.put("steps", mapper.readValue(String.valueOf(stepsJson), List.class));
            } catch (Exception e) {
                out.put("steps", List.of());
            }
        } else {
            out.put("steps", List.of());
        }
        return out;
    }

    // ==================== 调度辅助（TaskSchedulerHolder 专用） ====================

    /** 定时触发被防重拦截：记一条 SKIPPED run（不更新连续失败计数——上轮还在跑不算失败） */
    public void recordCronSkipped(long taskId, String reason) {
        controlJdbcTemplate.update("""
                INSERT INTO task_run (tenant_id, task_id, trigger_type, triggered_by, status,
                    finished_at, elapsed_ms, permission_fingerprint, error_msg)
                SELECT tenant_id, id, 'cron', owner_username, 'SKIPPED', NOW(3), 0, '-', ?
                FROM task_definition WHERE id=?
                """, truncate(reason, 1024), taskId);
    }

    /**
     * 定时执行整体失败（如创建者账号不存在）：记 FAILED run + 连续失败计数；
     * 达到熔停阈值返回 true（调用方负责 cancel 注册并置 PAUSED）。
     */
    public boolean noteCronFailure(long taskId, String error) {
        controlJdbcTemplate.update("""
                INSERT INTO task_run (tenant_id, task_id, trigger_type, triggered_by, status,
                    finished_at, elapsed_ms, permission_fingerprint, error_msg)
                SELECT tenant_id, id, 'cron', owner_username, 'FAILED', NOW(3), 0, '-', ?
                FROM task_definition WHERE id=?
                """, truncate(error, 1024), taskId);
        List<Map<String, Object>> rows = controlJdbcTemplate.queryForList(
                "SELECT consecutive_failures FROM task_definition WHERE id=?", taskId);
        if (rows.isEmpty()) {
            return false;
        }
        int failures = ((Number) rows.get(0).get("consecutive_failures")).intValue() + 1;
        controlJdbcTemplate.update("""
                UPDATE task_definition SET last_status='FAILED', last_fire_at=NOW(3),
                    consecutive_failures=?, status=IF(?, 'PAUSED', status)
                WHERE id=?
                """, failures, failures >= MAX_CONSECUTIVE_FAILURES, taskId);
        return failures >= MAX_CONSECUTIVE_FAILURES;
    }

    /** 读任务的 cron 表达式（恢复注册用） */
    public List<Map<String, Object>> listScheduledTasks() {
        return controlJdbcTemplate.queryForList(
                "SELECT id, cron_expr FROM task_definition WHERE schedule_enabled=1 AND status='ACTIVE'");
    }

    /** 查任务 owner（调度器权限重建用）；不存在/已归档返回 null */
    public String ownerOf(long taskId) {
        List<Map<String, Object>> rows = controlJdbcTemplate.queryForList(
                "SELECT owner_username, status FROM task_definition WHERE id=?", taskId);
        if (rows.isEmpty() || "ARCHIVED".equals(rows.get(0).get("status"))) {
            return null;
        }
        return String.valueOf(rows.get(0).get("owner_username"));
    }

    /**
     * 读会话最近一轮 assistant payload 的工具帧（save_task 工具跨轮回退用：
     * 「把刚才的存为任务」场景，本轮还没有查询帧时取上一轮）。
     */
    @SuppressWarnings("unchecked")
    public List<TaskStepRefiner.Frame> lastSessionFrames(String tenantId, String sessionId) {
        List<TaskStepRefiner.Frame> out = new ArrayList<>();
        for (Map<String, Object> msg : sessionStore.listMessages(tenantId, sessionId)) {
            if (!"assistant".equals(msg.get("role")) || !(msg.get("payload") instanceof Map<?, ?> payload)) {
                continue;
            }
            if (payload.get("tools") instanceof List<?> tools && !tools.isEmpty()) {
                for (Object t : tools) {
                    if (t instanceof Map<?, ?> m && m.get("args") instanceof Map<?, ?> args) {
                        out.add(new TaskStepRefiner.Frame(String.valueOf(m.get("id")),
                                String.valueOf(m.get("name")), (Map<String, Object>) args));
                    }
                }
                break; // 只取最近一轮
            }
        }
        return out;
    }

    /**
     * 统一覆盖任务各步骤的时间范围（save_task 的 time_range 参数）。
     * 按各步骤能力声明的 daterange 参数名写入相对词，写前预检可解析性。
     */
    public void overrideTimeRange(long taskId, String timeWord) throws Exception {
        Map<String, Object> task = controlJdbcTemplate.queryForMap(
                "SELECT steps_json FROM task_definition WHERE id=?", taskId);
        List<Map<String, Object>> steps = mapper.readValue(String.valueOf(task.get("steps_json")), List.class);
        for (Map<String, Object> step : steps) {
            String capabilityId = String.valueOf(step.get("capabilityId"));
            registry.get(capabilityId).ifPresent(def -> {
                for (CapabilityDefinition.ParamDef p : def.getParams()) {
                    if ("daterange".equals(p.getType()) && p.getName() != null) {
                        @SuppressWarnings("unchecked")
                        Map<String, Object> params = (Map<String, Object>) step.get("params");
                        if (params != null) {
                            params.put(p.getName(), timeWord);
                        }
                    }
                }
            });
        }
        controlJdbcTemplate.update("UPDATE task_definition SET steps_json=? WHERE id=?",
                mapper.writeValueAsString(steps), taskId);
    }

    /** 回写展示用 next_fire_at（恢复时由 CronTrigger 重算，避免 DB 时钟双源） */
    public void updateNextFireAt(long taskId, String next) {
        controlJdbcTemplate.update("UPDATE task_definition SET next_fire_at=? WHERE id=?", next, taskId);
    }

    // ==================== 内部工具 ====================

    /** 读任务并校验归属（本人或 admin）；ARCHIVED 视为不存在 */
    private Map<String, Object> requireTask(PermissionContext ctx, long id) {
        List<Map<String, Object>> rows = controlJdbcTemplate.queryForList(
                "SELECT * FROM task_definition WHERE tenant_id=? AND id=?", ctx.tenantId(), id);
        if (rows.isEmpty() || "ARCHIVED".equals(rows.get(0).get("status"))) {
            throw ApiException.paramInvalid("任务不存在: " + id);
        }
        if (!ctx.isAdmin() && !ctx.username().equals(String.valueOf(rows.get(0).get("owner_username")))) {
            throw ApiException.scopeDenied("只能操作自己创建的任务");
        }
        return rows.get(0);
    }

    /** steps_json 反序列化（容错：坏数据按空步骤处理） */
    @SuppressWarnings("unchecked")
    private List<Map<String, Object>> readSteps(Map<String, Object> task) {
        Object json = task.get("steps_json");
        if (json == null) {
            return List.of();
        }
        try {
            return mapper.readValue(String.valueOf(json), List.class);
        } catch (Exception e) {
            log.error("steps_json 解析失败 taskId={}", task.get("id"), e);
            return List.of();
        }
    }

    /** 插入 task_run，返回自增 id */
    private long insertRun(Map<String, Object> task, String triggerType, String triggeredBy,
                           String status, long elapsedMs, String fingerprint,
                           List<Map<String, Object>> stepResults, String conclusion, String error) throws Exception {
        controlJdbcTemplate.update("""
                INSERT INTO task_run (tenant_id, task_id, trigger_type, triggered_by, status,
                    finished_at, elapsed_ms, permission_fingerprint, steps_result_json, conclusion, error_msg)
                VALUES (?, ?, ?, ?, ?, NOW(3), ?, ?, ?, ?, ?)
                """,
                task.get("tenant_id"), task.get("id"), triggerType, triggeredBy, status,
                (int) Math.min(elapsedMs, Integer.MAX_VALUE), fingerprint,
                mapper.writeValueAsString(stepResults), conclusion, error);
        Long runId = controlJdbcTemplate.queryForObject(
                "SELECT id FROM task_run WHERE tenant_id=? AND task_id=? ORDER BY id DESC LIMIT 1",
                Long.class, task.get("tenant_id"), task.get("id"));
        return runId == null ? -1 : runId;
    }

    /** 任务是否启用分析报告（列缺省 1） */
    private static boolean analyzeEnabled(Map<String, Object> task) {
        Object v = task.get("analyze_report");
        return v == null || ((Number) v).intValue() != 0;
    }

    /**
     * 分析报告生成：把本次各步结果（能力名 + 行数 + 前 15 行）交给分析模型，
     * 生成「结论先行 + 证据数字 + 建议关注」的报告。
     * 模型不可用（mock）时降级为模板摘要（与 LlmClient 模板结论哲学一致）；失败返回 null，不阻塞任务。
     */
    private String generateReport(Map<String, Object> task, String triggerType,
                                   List<Map<String, Object>> stepResults) {
        CopilotRuntime runtime = runtimeProvider.getIfAvailable();
        if (runtime == null) {
            return templateReport(task, triggerType, stepResults);
        }
        try {
            StringBuilder user = new StringBuilder();
            user.append("任务：").append(task.get("title")).append('\n');
            user.append("触发方式：").append("cron".equals(triggerType) ? "定时自动执行" : "手动执行").append('\n');
            user.append("时间窗口：各步骤按相对时间词（如近7天）以执行当天现算\n\n");
            int idx = 0;
            for (Map<String, Object> step : stepResults) {
                if (!"SUCCESS".equals(step.get("status")) || step.get("result") == null) {
                    continue;
                }
                idx++;
                user.append("【步骤 ").append(step.get("seq")).append(" · ").append(step.get("displayName")).append("】\n");
                user.append(compactResult(step.get("result"))).append("\n\n");
            }
            StringBuilder report = new StringBuilder();
            runtime.streaming().stream(new com.dst.v2xagent.runtime.spi.StreamingModelClient.StreamingRequest(
                    TASK_REPORT_PROMPT, user.toString(), 0.3, 45000),
                    delta -> report.append(delta));
            return report.length() == 0 ? templateReport(task, triggerType, stepResults) : report.toString();
        } catch (Exception e) {
            log.warn("任务分析报告生成失败 taskId={}：{}", task.get("id"), e.getMessage());
            return templateReport(task, triggerType, stepResults);
        }
    }

    // ==================== 异常自动复核（报告标记 → anomaly_explain 深度取证） ====================

    /** 复核触发标记：<复核:车辆标识>（由 TASK_REPORT_PROMPT 规则 6 产出） */
    private static final java.util.regex.Pattern REVIEW_MARK =
            java.util.regex.Pattern.compile("<复核[:：]\\s*([^>\\s]{2,32}?)\\s*>");

    /**
     * 报告判定有异常时自动触发深度复核：对标记车辆执行 anomaly_explain 编排
     * （告警+故障+里程三路取证），复核表格追加为执行步骤、复核结论拼接进报告。
     * 复核失败不影响任务状态，仅在报告尾部说明。
     */
    private String autoReviewIfNeeded(Map<String, Object> task, String conclusion,
                                      List<Map<String, Object>> stepResults, PermissionContext ctx) {
        java.util.regex.Matcher m = REVIEW_MARK.matcher(conclusion);
        if (!m.find()) {
            return conclusion;
        }
        String vehicle = m.group(1);
        String timeRange = stepTimeRange(task);
        long t0 = System.currentTimeMillis();
        try {
            CapabilityService.InvokeOutcome outcome = capabilityService.invoke("anomaly_explain",
                    Map.of("vehicle", vehicle, "time_range", timeRange), ctx);
            Map<String, Object> step = new LinkedHashMap<>();
            step.put("seq", nextSeq(stepResults));
            step.put("capabilityId", "anomaly_explain");
            step.put("displayName", vehicle + " 异常复核（自动触发）");
            step.put("status", "SUCCESS");
            step.put("elapsedMs", System.currentTimeMillis() - t0);
            step.put("result", tablePayload(outcome.table()));
            step.put("review", true);
            stepResults.add(step);
            String reviewText = generateReviewText(vehicle, outcome);
            return conclusion + "\n\n---\n\n**【自动复核 · " + vehicle + "】**（报告判定异常后自动触发三路取证：告警 / 故障 / 里程）\n\n"
                    + reviewText;
        } catch (Exception e) {
            log.warn("自动复核执行失败 taskId={} vehicle={}：{}", task.get("id"), vehicle, rootMessage(e));
            return conclusion + "\n\n> 自动复核执行失败：" + rootMessage(e)
                    + "。可在任务中心重新执行该任务，或在对话中对车辆发起异常分析。";
        }
    }

    /** 从任务步骤参数中取时间范围相对词（复核取证窗口与巡检一致），缺省近7天 */
    private String stepTimeRange(Map<String, Object> task) {
        for (Map<String, Object> step : readSteps(task)) {
            if (step.get("params") instanceof Map<?, ?> p && p.get("time_range") != null) {
                return String.valueOf(p.get("time_range"));
            }
        }
        return "近7天";
    }

    /** 复核步骤序号：现有最大 seq + 1 */
    private static int nextSeq(List<Map<String, Object>> stepResults) {
        int max = 0;
        for (Map<String, Object> s : stepResults) {
            if (s.get("seq") instanceof Number n) {
                max = Math.max(max, n.intValue());
            }
        }
        return max + 1;
    }

    /** 复核结论段：三路取证汇总表交给分析模型（mock/失败降级模板），确认异常 / 证据 / 处置建议 */
    private String generateReviewText(String vehicle, CapabilityService.InvokeOutcome outcome) {
        CopilotRuntime runtime = runtimeProvider.getIfAvailable();
        if (runtime == null) {
            return templateReviewText(vehicle, outcome);
        }
        try {
            StringBuilder user = new StringBuilder();
            user.append("复核对象车辆：").append(vehicle).append('\n');
            user.append("触发原因：巡检任务分析报告判定该车辆存在需关注的异常，已自动完成三路取证。\n\n");
            user.append("【三路取证汇总（告警 / 故障 / 里程）】\n");
            user.append(compactResult(tablePayload(outcome.table())));
            StringBuilder report = new StringBuilder();
            runtime.streaming().stream(new com.dst.v2xagent.runtime.spi.StreamingModelClient.StreamingRequest(
                    TASK_REVIEW_PROMPT, user.toString(), 0.3, 45000),
                    delta -> report.append(delta));
            return report.length() == 0 ? templateReviewText(vehicle, outcome) : report.toString();
        } catch (Exception e) {
            log.warn("复核结论生成失败 vehicle={}：{}", vehicle, e.getMessage());
            return templateReviewText(vehicle, outcome);
        }
    }

    /** 模板复核结论（mock/降级路径）：只引用取证表数值 */
    private String templateReviewText(String vehicle, CapabilityService.InvokeOutcome outcome) {
        TableResult table = outcome.table();
        StringBuilder sb = new StringBuilder();
        sb.append("**复核结论**：已完成对 ").append(vehicle).append(" 的三路取证（告警/故障/里程），共取回 ")
                .append(table.rowCount()).append(" 行数据，明细见下方复核步骤表格。");
        if (table.rowCount() > 0 && !table.columns().isEmpty()) {
            sb.append(" 首条：");
            for (int c = 0; c < Math.min(3, table.columns().size()); c++) {
                if (c > 0) sb.append("，");
                sb.append(table.rows().get(0)[c]);
            }
            sb.append("。");
        }
        sb.append("\n\n注：分析模型不可用，以上为数据摘要（模板生成），请人工研判。");
        return sb.toString();
    }

    /** 复核结论系统提示词 */
    private static final String TASK_REVIEW_PROMPT =
            "你是车联网平台的异常复核 Agent。巡检任务判定某车辆存在异常后，系统自动完成三路取证"
            + "（告警统计/故障明细/里程趋势），你基于取证数据输出复核结论。\n要求：\n"
            + "1. 复核判定：确认异常存在并说明性质，或数据不支撑（建议降级观察）；\n"
            + "2. 证据分点：引用具体数字（次数、日期、趋势）；\n"
            + "3. 处置建议：是否需要检修、是否建议停运、观察窗口；\n"
            + "4. 数据不足时如实说明，严禁编造数字；\n"
            + "5. Markdown 格式，150 字以内；语气专业客观，不用 emoji。";

    /** 模板分析报告（mock/降级路径）：只引用结果集数值，绝不算新数 */
    private String templateReport(Map<String, Object> task, String triggerType,
                                   List<Map<String, Object>> stepResults) {
        StringBuilder sb = new StringBuilder();
        sb.append("**总体结论**：任务「").append(task.get("title")).append("」")
                .append("cron".equals(triggerType) ? "（定时执行）" : "（手动执行）")
                .append("已完成，各步骤结果如下。\n\n");
        for (Map<String, Object> step : stepResults) {
            if (!"SUCCESS".equals(step.get("status")) || !(step.get("result") instanceof Map<?, ?> r)) {
                continue;
            }
            sb.append("- **").append(step.get("displayName")).append("**：共 ").append(r.get("rowCount")).append(" 行");
            if (r.get("rows") instanceof List<?> rows && !rows.isEmpty()
                    && rows.get(0) instanceof List<?> first) {
                sb.append("，首条：");
                int cols = Math.min(3, first.size());
                for (int c = 0; c < cols; c++) {
                    if (c > 0) sb.append("，");
                    sb.append(first.get(c));
                }
            }
            sb.append('\n');
        }
        sb.append("\n注：分析模型不可用，以上为数据摘要（模板生成）。");
        return sb.toString();
    }

    /** 步骤结果紧凑化（给报告模型：列名 + 行数 + 前 15 行；列是内存 ColumnDef 对象，须经 Jackson 转换） */
    private String compactResult(Object resultObj) {
        try {
            if (!(resultObj instanceof Map<?, ?> result)) {
                return "{}";
            }
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("rowCount", result.get("rowCount"));
            Object columns = result.get("columns");
            if (columns != null) {
                List<Map<String, Object>> colMaps = mapper.convertValue(columns,
                        new com.fasterxml.jackson.core.type.TypeReference<List<Map<String, Object>>>() {});
                m.put("columns", colMaps.stream()
                        .map(c -> String.valueOf(c.get("display")) + (c.get("unit") == null ? "" : "(" + c.get("unit") + ")"))
                        .toList());
            }
            m.put("rows", result.get("rows"));
            return mapper.writeValueAsString(m);
        } catch (Exception e) {
            log.warn("compactResult failed: {}", e.getMessage());
            return "{}";
        }
    }

    /** 任务分析报告系统提示词（表格已展示给用户，正文只给判断与数字） */
    private static final String TASK_REPORT_PROMPT =
            "你是车联网平台的任务分析报告 Agent。用户把固定查询固化为任务（手动或定时执行），"
            + "每次执行后你基于各步骤的查询结果生成简短分析报告。\n要求：\n"
            + "1. 第一行一句话总体结论（数据是否正常、有无需要关注的异常）；\n"
            + "2. 证据分点：引用具体数字（次数、行数、趋势、Top 项）；\n"
            + "3. 与任务用途相关的建议（是否需要人工跟进）；\n"
            + "4. 数据不足或为空时如实说明，严禁编造数字；\n"
            + "5. Markdown 格式，全文不超过 250 字；语气专业客观，不用 emoji；\n"
            + "6. 复核触发标记（必须严格遵守的输出格式）：判断该车辆存在需要人工关注的异常时，"
            + "报告的最后一行必须原样输出复核标记，格式如 <复核:粤C10003>——"
            + "冒号后取查询参数中的车牌或 VIN（与巡检对象一致）；"
            + "该标记独占一行、放在报告最末尾、不加任何其他文字；"
            + "无异常或数据不足时禁止输出该标记。系统据此自动触发三路取证深度复核，"
            + "只要你在建议中提到需要复核/人工跟进/检修，就必须输出该标记。";

    /** 表格载荷（与 /ag-ui/tool-result 同构：columns + rows≤20 字符串化 + freshness） */
    private Map<String, Object> tablePayload(TableResult table) {
        Map<String, Object> p = new LinkedHashMap<>();
        p.put("columns", table.columns());
        p.put("rows", table.rows().stream().limit(20)
                .map(r -> java.util.Arrays.stream(r).map(c -> c == null ? null : c.toString()).toList())
                .toList());
        p.put("rowCount", table.rowCount());
        p.put("truncated", table.truncated());
        p.put("freshness", Map.of(
                "dataAsOf", table.freshness().dataAsOf(),
                "policyType", table.freshness().policyType(),
                "expectedDelayMin", table.freshness().expectedDelayMin()));
        return p;
    }

    /** cron 未来 n 次触发时间（Asia/Shanghai 墙钟，yyyy-MM-dd HH:mm:ss）；表达式非法抛 ApiException */
    public static List<String> nextFireTimes(String cronExpr, int count) {
        try {
            org.springframework.scheduling.support.CronExpression cron =
                    org.springframework.scheduling.support.CronExpression.parse(cronExpr.trim());
            java.time.ZonedDateTime next = java.time.ZonedDateTime.now(java.time.ZoneId.of("Asia/Shanghai"));
            List<String> out = new ArrayList<>();
            for (int i = 0; i < count; i++) {
                next = cron.next(next);
                if (next == null) {
                    break;
                }
                out.add(next.format(java.time.format.DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss")));
            }
            if (out.isEmpty()) {
                throw ApiException.paramInvalid("cron 表达式不会触发: " + cronExpr);
            }
            return out;
        } catch (IllegalArgumentException e) {
            throw ApiException.paramInvalid("cron 表达式非法: " + cronExpr);
        }
    }

    private static String truncate(String s, int max) {
        if (s == null) {
            return null;
        }
        return s.length() > max ? s.substring(0, max) : s;
    }

    private static String rootMessage(Throwable e) {
        Throwable t = e;
        while (t.getCause() != null) {
            t = t.getCause();
        }
        String msg = String.valueOf(t.getMessage());
        return truncate(msg, 512);
    }
}

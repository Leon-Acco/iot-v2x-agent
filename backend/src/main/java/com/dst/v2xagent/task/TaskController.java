package com.dst.v2xagent.task;

import com.dst.v2xagent.agui.auth.AuthFilter;
import com.dst.v2xagent.common.ApiException;
import com.dst.v2xagent.common.PermissionContext;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.Map;

/**
 * 任务卡 2.0 端点：会话流程精炼（POST /ag-ui/task）→ 一键执行（POST .../run）
 * → 定时调度（POST .../schedule）→ 执行历史（GET .../runs、GET /ag-ui/task-run/{id}）。
 * 写操作仅任务创建者或 admin；权限上下文由 AuthFilter 注入。
 * 调度注册/取消由本控制器编排 TaskSchedulerHolder（避免 Service↔Holder 循环依赖）。
 */
@Slf4j
@RestController
@RequestMapping("/ag-ui")
@RequiredArgsConstructor
public class TaskController {

    private final TaskService taskService;
    private final TaskSchedulerHolder schedulerHolder;
    private final TaskRunExportService runExportService;

    public record CreateBody(String title, List<TaskStepRefiner.Frame> steps,
                             String sourceSessionId, String sourceQuestion, Boolean analyzeReport) {}

    public record UpdateBody(String title, Integer timeoutSeconds) {}

    public record ScheduleBody(boolean enabled, String cronExpr) {}

    /** 从会话工具帧精炼创建任务 */
    @PostMapping("/task")
    public Map<String, Object> create(@Valid @RequestBody CreateBody body, HttpServletRequest request) {
        PermissionContext ctx = permission(request);
        if (body.steps() == null || body.steps().isEmpty()) {
            throw ApiException.paramInvalid("steps 不能为空");
        }
        return taskService.createFromSession(ctx, new TaskService.CreateRequest(
                body.title(), body.steps(), body.sourceSessionId(), body.sourceQuestion(),
                body.analyzeReport()));
    }

    /** 我的任务列表（admin 看全租户） */
    @GetMapping("/task/mine")
    public List<Map<String, Object>> mine(HttpServletRequest request) {
        return taskService.listMine(permission(request));
    }

    /** 任务详情（steps + 最近一次执行摘要） */
    @GetMapping("/task/{id}")
    public Map<String, Object> detail(@PathVariable long id, HttpServletRequest request) {
        return taskService.get(permission(request), id);
    }

    /** 更新标题/执行预算 */
    @PutMapping("/task/{id}")
    public Map<String, Object> update(@PathVariable long id, @RequestBody UpdateBody body,
                                      HttpServletRequest request) {
        taskService.update(permission(request), id, new TaskService.UpdateRequest(
                body.title(), body.timeoutSeconds()));
        return Map.of("success", true);
    }

    /** 归档任务（同时取消定时注册） */
    @DeleteMapping("/task/{id}")
    public Map<String, Object> delete(@PathVariable long id, HttpServletRequest request) {
        taskService.delete(permission(request), id);
        schedulerHolder.cancel(id);
        return Map.of("success", true);
    }

    /** 一键执行：同步返回每步表格结果（与 /ag-ui/tool-result 同构；上界 6 步 × 15s） */
    @PostMapping("/task/{id}/run")
    public Map<String, Object> run(@PathVariable long id, HttpServletRequest request) {
        return taskService.runOnce(id, "manual", permission(request));
    }

    /** 执行历史列表 */
    @GetMapping("/task/{id}/runs")
    public List<Map<String, Object>> runs(@PathVariable long id,
                                          @RequestParam(required = false, defaultValue = "20") int limit,
                                          HttpServletRequest request) {
        return taskService.listRuns(permission(request), id, limit);
    }

    /** 单次执行详情（含每步表格；P3 异步化时接口面不变） */
    @GetMapping("/task-run/{runId}")
    public Map<String, Object> runDetail(@PathVariable long runId, HttpServletRequest request) {
        return taskService.getRun(permission(request), runId);
    }

    /** 单次执行记录 PDF 导出（元信息 + 分析报告 + 各步骤数据表，合并旧任务卡后的导出承接） */
    @GetMapping(value = "/task-run/{runId}/export.pdf", produces = MediaType.APPLICATION_PDF_VALUE)
    public ResponseEntity<byte[]> exportRunPdf(@PathVariable long runId, HttpServletRequest request) {
        byte[] pdf = runExportService.export(permission(request), runId);
        return ResponseEntity.ok()
                .header(HttpHeaders.CONTENT_DISPOSITION, "attachment; filename=task-run-" + runId + ".pdf")
                .contentType(MediaType.APPLICATION_PDF)
                .body(pdf);
    }

    /** 定时设置：启用即校验 cron 并注册；停用即取消（手动执行不受影响） */
    @PostMapping("/task/{id}/schedule")
    public Map<String, Object> schedule(@PathVariable long id, @RequestBody ScheduleBody body,
                                        HttpServletRequest request) {
        Map<String, Object> out = taskService.setSchedule(permission(request), id,
                new TaskService.ScheduleRequest(body.enabled(), body.cronExpr()));
        if (body.enabled() && body.cronExpr() != null) {
            schedulerHolder.schedule(id, body.cronExpr().trim());
        } else {
            schedulerHolder.cancel(id);
        }
        return out;
    }

    /** cron 未来 3 次触发预览（前端设置对话框用） */
    @GetMapping("/task/sched/preview")
    public Map<String, Object> preview(@RequestParam("cron") @NotBlank String cron) {
        return Map.of("next", TaskService.nextFireTimes(cron, 3));
    }

    /** 从请求属性取权限上下文（AuthFilter 已注入） */
    private static PermissionContext permission(HttpServletRequest request) {
        PermissionContext ctx = (PermissionContext) request.getAttribute(AuthFilter.ATTR_PERMISSION);
        if (ctx == null) {
            throw ApiException.unauthorized("未认证");
        }
        return ctx;
    }
}

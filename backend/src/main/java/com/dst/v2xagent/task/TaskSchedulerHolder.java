package com.dst.v2xagent.task;

import com.dst.v2xagent.agui.auth.AuthService;
import com.dst.v2xagent.common.PermissionContext;
import jakarta.annotation.PostConstruct;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;
import org.springframework.scheduling.concurrent.ThreadPoolTaskScheduler;
import org.springframework.scheduling.support.CronTrigger;
import org.springframework.stereotype.Component;

import java.time.ZoneId;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * 定时任务调度持有者：动态 cron 注册/取消 + 启动恢复 + 防重 + 连续失败熔停。
 * 时区显式 Asia/Shanghai（服务器/容器可能是 UTC，cron 语义不能漂移）。
 *
 * 防循环依赖：Holder → TaskService 单向依赖；TaskService 不回持 Holder，
 * schedule/delete 时的注册取消由 TaskController 编排（Controller → Service + Holder）。
 *
 * 单实例假设：防重用进程内 AtomicBoolean；多实例部署会双跑，
 * 扩实例前必须改为 DB 抢占（next_fire_at CAS 或 GET_LOCK）。
 */
@Slf4j
@Component
public class TaskSchedulerHolder {

    private final TaskService taskService;
    private final AuthService authService;
    private final ThreadPoolTaskScheduler triggerScheduler;
    private final ThreadPoolTaskExecutor execExecutor;

    /** 已注册的定时任务（taskId → future） */
    private final ConcurrentHashMap<Long, ScheduledFuture<?>> registered = new ConcurrentHashMap<>();
    /** 执行中标记（防重：上轮未结束跳过本轮） */
    private final ConcurrentHashMap<Long, AtomicBoolean> inflight = new ConcurrentHashMap<>();

    private static final ZoneId ZONE = ZoneId.of("Asia/Shanghai");

    public TaskSchedulerHolder(TaskService taskService, AuthService authService,
                               @Qualifier("taskTriggerScheduler") ThreadPoolTaskScheduler triggerScheduler,
                               @Qualifier("taskExecExecutor") ThreadPoolTaskExecutor execExecutor) {
        this.taskService = taskService;
        this.authService = authService;
        this.triggerScheduler = triggerScheduler;
        this.execExecutor = execExecutor;
    }

    /** 启动恢复：扫描启用的定时任务逐个注册；next_fire_at 以 CronTrigger 重算回写（DB 值仅展示） */
    @PostConstruct
    void recover() {
        try {
            int count = 0;
            for (Map<String, Object> row : taskService.listScheduledTasks()) {
                long id = ((Number) row.get("id")).longValue();
                String cron = String.valueOf(row.get("cron_expr"));
                try {
                    schedule(id, cron);
                    refreshNextFireAt(id, cron);
                    count++;
                } catch (Exception e) {
                    log.error("定时任务恢复失败 id={} cron={}", id, cron, e);
                }
            }
            if (count > 0) {
                log.info("定时任务恢复完成：{} 个已注册", count);
            }
        } catch (Exception e) {
            // 控制库未就绪等场景不阻塞启动（fail-open，与 Nacos fail-fast=false 同思路）
            log.warn("定时任务恢复跳过（启动早期或库未就绪）：{}", e.getMessage());
        }
    }

    /** 注册（幂等：已注册先取消再注册，覆盖旧 cron） */
    public void schedule(long taskId, String cronExpr) {
        registered.compute(taskId, (id, old) -> {
            if (old != null) {
                old.cancel(false);
            }
            return triggerScheduler.schedule(() -> fire(taskId), new CronTrigger(cronExpr, ZONE));
        });
    }

    /** 取消注册（归档/停用定时；inflight 标记保留——执行中的这轮跑完自然释放） */
    public void cancel(long taskId) {
        ScheduledFuture<?> future = registered.remove(taskId);
        if (future != null) {
            future.cancel(false);
        }
    }

    /**
     * 到点触发：必须毫秒级返回（运行在 cron 线程）。
     * 防重 → 提交执行池 → 按创建者当前权限重建上下文 → runOnce("cron")。
     */
    private void fire(long taskId) {
        AtomicBoolean running = inflight.computeIfAbsent(taskId, k -> new AtomicBoolean(false));
        if (!running.compareAndSet(false, true)) {
            taskService.recordCronSkipped(taskId, "上轮执行未结束，本轮跳过");
            return;
        }
        boolean accepted = false;
        try {
            execExecutor.execute(() -> {
                try {
                    executeCron(taskId);
                } finally {
                    running.set(false);
                }
            });
            accepted = true;
        } catch (org.springframework.core.task.TaskRejectedException e) {
            log.warn("任务 {} 执行队列已满，记 SKIPPED", taskId);
        } finally {
            if (!accepted) {
                running.set(false);
                taskService.recordCronSkipped(taskId, "执行队列已满，本轮跳过");
            }
        }
    }

    /** cron 执行体（运行在执行线程池）：权限重建 → runOnce；创建者失效/连续失败走熔停 */
    private void executeCron(long taskId) {
        String owner = ownerOf(taskId);
        if (owner == null) {
            log.warn("定时任务 {} 的定义已不存在，跳过", taskId);
            return;
        }
        Optional<PermissionContext> ctx = authService.resolveByUsername(owner);
        if (ctx.isEmpty()) {
            boolean paused = taskService.noteCronFailure(taskId, "创建者账号不存在或已失效：" + owner);
            if (paused) {
                cancel(taskId);
                log.warn("定时任务 {} 连续失败达到阈值，已自动暂停", taskId);
            }
            return;
        }
        try {
            Map<String, Object> result = taskService.runOnce(taskId, "cron", ctx.get());
            if (Boolean.TRUE.equals(result.get("autoPaused"))) {
                cancel(taskId);
                log.warn("定时任务 {} 连续失败达到阈值，已自动暂停", taskId);
            }
        } catch (Exception e) {
            boolean paused = taskService.noteCronFailure(taskId, "执行异常：" + e.getMessage());
            if (paused) {
                cancel(taskId);
            }
            log.error("定时任务 {} 执行异常", taskId, e);
        }
    }

    /** 查任务 owner（轻量直查，避免循环引入 Service 大方法） */
    private String ownerOf(long taskId) {
        // 直接借用 service 的 listScheduledTasks 不合适；此处经 noteCronFailure 系列的同款表访问
        // 简化处理：runOnce 内部 requireTask 已覆盖不存在场景，这里仅取 owner 供权限重建
        try {
            return taskService.ownerOf(taskId);
        } catch (Exception e) {
            return null;
        }
    }

    /** 重算展示用 next_fire_at 并回写（复用 CronExpression 计算，DB 值仅展示） */
    private void refreshNextFireAt(long taskId, String cronExpr) {
        try {
            String next = TaskService.nextFireTimes(cronExpr, 1).get(0);
            taskService.updateNextFireAt(taskId, next);
        } catch (Exception ignore) {
            // 展示字段失败不影响调度
        }
    }
}

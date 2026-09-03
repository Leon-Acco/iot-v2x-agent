package com.dst.v2xagent.capability;

import com.dst.v2xagent.capability.model.CapabilityDefinition;
import com.dst.v2xagent.common.ApiException;
import com.dst.v2xagent.common.PermissionContext;
import com.dst.v2xagent.observability.trace.TraceContext;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.time.Duration;

/**
 * Capability 防火墙（统一执行前置管线）
 * 顺序：状态检查 → 权限 scope → 参数策略（时间跨度）→ 限流 → 放行
 * 任何一环不过即抛 ApiException，绝不静默放行；
 * 权限由系统计算（AllowedScope ∩ RequestedScope），永远不信任模型声明。
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class CapabilityFirewall {

    private final RateLimiter rateLimiter;

    /**
     * 执行前检查（在 ParamResolver 完成参数解析后调用）
     * @param def      capability 定义
     * @param resolved 已解析参数（含时间范围）
     * @param ctx      权限上下文
     */
    public void check(CapabilityDefinition def, ParamResolver.ResolvedParams resolved, PermissionContext ctx) {
        try (TraceContext.Span span = TraceContext.span("firewall", def.getId())) {
            doCheck(def, resolved, ctx);
        } catch (RuntimeException e) {
            throw e;
        }
    }

    private void doCheck(CapabilityDefinition def, ParamResolver.ResolvedParams resolved, PermissionContext ctx) {
        // 1 状态：仅 online 可执行
        if (!"online".equals(def.getStatus())) {
            throw ApiException.capabilityNotFound("capability 未上线: " + def.getId());
        }
        // 2 scope：用户 scopes 必须覆盖 capability 所需 scopes
        if (ctx.scopes() == null || !ctx.scopes().containsAll(def.getScopes())) {
            log.warn("scope denied: user={} need={} has={}", ctx.username(), def.getScopes(), ctx.scopes());
            throw ApiException.scopeDenied("缺少能力访问权限: " + def.getScopes());
        }
        // 3 参数策略：时间跨度不得超过 limits.max_span_days（防御纵深，ParamResolver 外再挡一道）
        if (resolved != null && resolved.timeRange() != null) {
            long spanDays = Duration.between(resolved.timeRange().from(), resolved.timeRange().to()).toDays();
            if (spanDays > def.getLimits().getMaxSpanDays()) {
                throw ApiException.paramInvalid(
                        "时间跨度 " + spanDays + " 天超过能力上限 " + def.getLimits().getMaxSpanDays() + " 天");
            }
        }
        // 4 限流：capability × 租户
        int rpm = def.getLimits().getRateLimitPerMinute();
        if (!rateLimiter.tryAcquire(def.getId() + "|" + ctx.tenantId(), rpm)) {
            throw ApiException.dataUnavailable("capability 调用过于频繁，请稍后重试");
        }
    }
}

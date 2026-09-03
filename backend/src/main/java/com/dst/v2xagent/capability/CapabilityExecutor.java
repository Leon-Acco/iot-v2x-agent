package com.dst.v2xagent.capability;

import com.dst.v2xagent.capability.model.CapabilityDefinition;
import com.dst.v2xagent.capability.model.TableResult;
import com.dst.v2xagent.common.ApiException;
import com.dst.v2xagent.common.PermissionContext;
import com.dst.v2xagent.observability.trace.TraceContext;
import com.dst.v2xagent.query.QueryBudget;
import com.dst.v2xagent.query.QueryExecutor;
import com.dst.v2xagent.query.QueryOutcome;
import io.github.resilience4j.circuitbreaker.CallNotPermittedException;
import io.github.resilience4j.circuitbreaker.CircuitBreaker;
import io.github.resilience4j.circuitbreaker.CircuitBreakerConfig;
import io.github.resilience4j.circuitbreaker.CircuitBreakerRegistry;
import jakarta.annotation.PostConstruct;
import lombok.extern.slf4j.Slf4j;
import org.springframework.dao.QueryTimeoutException;
import org.springframework.stereotype.Component;

import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;

/**
 * capability 执行器：熔断 + 结果缓存 + Trace span
 * 真正的 JDBC 执行统一委托 QueryExecutor（超时 / 截断 / 慢查询 / 异常映射）。
 * 护栏清单（文档 §6.5）：LIMIT 强制重写、查询超时、熔断隔离、截断可见。
 */
@Slf4j
@Component
public class CapabilityExecutor {

    private final QueryExecutor queryExecutor;
    private final ResultCache resultCache;

    public CapabilityExecutor(QueryExecutor queryExecutor, ResultCache resultCache) {
        this.queryExecutor = queryExecutor;
        this.resultCache = resultCache;
    }

    private CircuitBreakerRegistry circuitBreakerRegistry;

    @PostConstruct
    void init() {
        // per-capability 熔断：失败率 50% / 20 请求窗口
        CircuitBreakerConfig config = CircuitBreakerConfig.custom()
                .failureRateThreshold(50)
                .slidingWindowSize(20)
                .build();
        circuitBreakerRegistry = CircuitBreakerRegistry.of(config);
    }

    /**
     * 执行 capability 查询。
     * @param def      capability 定义
     * @param resolved 已完成八步解析的参数
     * @param ctx      权限上下文（进缓存 key）
     */
    public TableResult execute(CapabilityDefinition def, ParamResolver.ResolvedParams resolved, PermissionContext ctx) {
        // 结果缓存：同 capability + 同参数 + 同权限上下文命中直接返回（freshness 取快照时刻）
        String cacheKey = def.getId() + "|" + def.getVersion() + "|" + resolved.fingerprint() + "|" + ctx.fingerprint();
        if (def.getCache().isCacheable()) {
            TableResult cached = resultCache.get(cacheKey);
            if (cached != null) {
                log.debug("capability {} 命中缓存", def.getId());
                return cached;
            }
        }

        CircuitBreaker cb = circuitBreakerRegistry.circuitBreaker("cap-" + def.getId());
        TableResult result;
        try (TraceContext.Span span = TraceContext.span("capability", def.getId())) {
            result = CircuitBreaker.decorateCheckedSupplier(cb, () -> doExecute(def, resolved, ctx)).get();
        } catch (CallNotPermittedException e) {
            throw ApiException.dataUnavailable("数据源暂不可用（熔断保护中），请稍后重试");
        } catch (QueryTimeoutException e) {
            throw ApiException.dataTimeout("数据查询超时，建议缩小时间范围");
        } catch (ApiException e) {
            throw e;
        } catch (Throwable e) {
            log.error("capability {} 执行异常", def.getId(), e);
            throw ApiException.dataUnavailable("数据查询失败: " + e.getMessage());
        }

        if (def.getCache().isCacheable()) {
            resultCache.put(cacheKey, result, def.getCache().getTtlSeconds());
        }
        return result;
    }

    /** 真实执行：渲染模板 → 重写 LIMIT → 统一 QueryExecutor → 截断检测 */
    private TableResult doExecute(CapabilityDefinition def, ParamResolver.ResolvedParams resolved, PermissionContext ctx) {
        int maxRows = def.getLimits().getMaxRows();
        SqlTemplateRenderer.RenderedSql rendered = SqlTemplateRenderer.render(
                def.getSqlTemplate(), resolved.bindParams(), 100);
        // LIMIT 强制重写为 min(模板值, max_rows)，并多查 1 行用于截断检测
        String sql = rendered.sql().replaceAll("(?i)LIMIT\\s+\\d+\\s*;?\\s*$", "LIMIT " + (maxRows + 1));

        // 查询预算：由 capability 限额驱动
        QueryBudget budget = new QueryBudget(
                def.getLimits().getTimeoutMs(), maxRows,
                def.getLimits().getMaxScanRows(), def.getLimits().getMaxSpanDays());
        QueryOutcome outcome = queryExecutor.query(sql, rendered.bindValues(), budget, def.getId(), ctx);

        TableResult.Freshness freshness = new TableResult.Freshness(
                LocalDateTime.now().format(DateTimeFormatter.ofPattern("HH:mm")),
                def.getFreshnessPolicy() != null ? def.getFreshnessPolicy().getType() : "t_plus_0",
                def.getFreshnessPolicy() != null ? def.getFreshnessPolicy().getExpectedDelayMin() : 5,
                null);
        TableResult.ExecStats stats = new TableResult.ExecStats(outcome.elapsedMs(), outcome.rows().size(), rendered.maskedSnapshot());
        return new TableResult(def.getReturns().getColumns(), outcome.rows(), outcome.rows().size(),
                outcome.truncated(), freshness, stats);
    }
}

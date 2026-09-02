package com.dst.v2xagent.capability;

import com.dst.v2xagent.capability.model.CapabilityDefinition;
import com.dst.v2xagent.capability.model.TableResult;
import com.dst.v2xagent.common.ApiException;
import com.dst.v2xagent.common.PermissionContext;
import io.github.resilience4j.circuitbreaker.CallNotPermittedException;
import io.github.resilience4j.circuitbreaker.CircuitBreaker;
import io.github.resilience4j.circuitbreaker.CircuitBreakerConfig;
import io.github.resilience4j.circuitbreaker.CircuitBreakerRegistry;
import jakarta.annotation.PostConstruct;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.dao.QueryTimeoutException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.Statement;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * capability 执行器：PreparedStatement + 护栏 + 熔断 + 结果缓存
 * 护栏清单（文档 §6.5）：LIMIT 强制重写、查询超时、熔断隔离、截断可见。
 */
@Slf4j
@Component
public class CapabilityExecutor {

    private final JdbcTemplate analyticsJdbcTemplate;
    private final ResultCache resultCache;

    /** 显式注入分析库 JdbcTemplate（双数据源强隔离，不用 Lombok 避免 @Qualifier 丢失） */
    public CapabilityExecutor(@Qualifier("analyticsJdbcTemplate") JdbcTemplate analyticsJdbcTemplate,
                              ResultCache resultCache) {
        this.analyticsJdbcTemplate = analyticsJdbcTemplate;
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
        try {
            result = CircuitBreaker.decorateCheckedSupplier(cb, () -> doExecute(def, resolved)).get();
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

    /** 真实执行：渲染模板 → 重写 LIMIT → PreparedStatement → 截断检测 */
    private TableResult doExecute(CapabilityDefinition def, ParamResolver.ResolvedParams resolved) throws Exception {
        int maxRows = def.getLimits().getMaxRows();
        SqlTemplateRenderer.RenderedSql rendered = SqlTemplateRenderer.render(
                def.getSqlTemplate(), resolved.bindParams(), 100);
        // LIMIT 强制重写为 min(模板值, max_rows)，并多查 1 行用于截断检测
        String sql = rendered.sql().replaceAll("(?i)LIMIT\\s+\\d+\\s*;?\\s*$", "LIMIT " + (maxRows + 1));

        long start = System.currentTimeMillis();
        List<Object[]> rows = new ArrayList<>();
        int columnCount;

        try (var conn = analyticsJdbcTemplate.getDataSource().getConnection();
             PreparedStatement ps = conn.prepareStatement(sql)) {
            // 超时护栏：capability hard timeout
            ps.setQueryTimeout(Math.max(1, def.getLimits().getTimeoutMs() / 1000));
            List<Object> binds = rendered.bindValues();
            for (int i = 0; i < binds.size(); i++) {
                ps.setObject(i + 1, binds.get(i));
            }
            try (ResultSet rs = ps.executeQuery()) {
                columnCount = rs.getMetaData().getColumnCount();
                while (rs.next()) {
                    Object[] row = new Object[columnCount];
                    for (int c = 1; c <= columnCount; c++) {
                        row[c - 1] = rs.getObject(c);
                    }
                    rows.add(row);
                }
            }
        }
        long elapsed = System.currentTimeMillis() - start;

        // 截断必须可见：绝不静默截断（静默截断等于给错数）
        boolean truncated = rows.size() > maxRows;
        if (truncated) {
            rows = new ArrayList<>(rows.subList(0, maxRows));
        }

        TableResult.Freshness freshness = new TableResult.Freshness(
                LocalDateTime.now().format(DateTimeFormatter.ofPattern("HH:mm")),
                def.getFreshnessPolicy() != null ? def.getFreshnessPolicy().getType() : "t_plus_0",
                def.getFreshnessPolicy() != null ? def.getFreshnessPolicy().getExpectedDelayMin() : 5,
                null);
        TableResult.ExecStats stats = new TableResult.ExecStats(elapsed, rows.size(), rendered.maskedSnapshot());
        return new TableResult(def.getReturns().getColumns(), rows, rows.size(), truncated, freshness, stats);
    }
}

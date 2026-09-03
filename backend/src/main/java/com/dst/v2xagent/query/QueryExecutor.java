package com.dst.v2xagent.query;

import com.dst.v2xagent.common.ApiException;
import com.dst.v2xagent.common.PermissionContext;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLTimeoutException;
import java.util.ArrayList;
import java.util.List;

/**
 * 统一查询执行器：分析库（Doris）所有 SQL 的唯一出口
 * 职责：PreparedStatement 参数绑定、查询超时、行数上限 + 截断检测、
 * 慢查询记录、异常统一映射（timeout / unavailable）。
 * Capability / Copilot / Map 等数据访问层一律经由本类，不允许自建 JDBC。
 */
@Slf4j
@Component
public class QueryExecutor {

    private final JdbcTemplate analyticsJdbcTemplate;
    private final SlowQueryRecorder slowQueryRecorder;

    /** 显式注入分析库 JdbcTemplate（双数据源强隔离，不用 Lombok 避免 @Qualifier 丢失） */
    public QueryExecutor(@Qualifier("analyticsJdbcTemplate") JdbcTemplate analyticsJdbcTemplate,
                         SlowQueryRecorder slowQueryRecorder) {
        this.analyticsJdbcTemplate = analyticsJdbcTemplate;
        this.slowQueryRecorder = slowQueryRecorder;
    }

    /**
     * 执行一次只读查询。
     * @param sql    只含 ? 占位的 SQL（禁止拼接业务参数）
     * @param binds  绑定参数
     * @param budget 查询预算（超时 / 行数上限）
     * @param source 调用来源标识（capability id / controller 名，进慢查询日志）
     * @param ctx    权限上下文（慢查询归因用，可为 null）
     */
    public QueryOutcome query(String sql, List<Object> binds, QueryBudget budget, String source, PermissionContext ctx) {
        long start = System.currentTimeMillis();
        List<Object[]> rows = new ArrayList<>();
        int columnCount = 0;
        try (var conn = analyticsJdbcTemplate.getDataSource().getConnection();
             PreparedStatement ps = conn.prepareStatement(sql)) {
            // 超时护栏：预算驱动的 hard timeout
            ps.setQueryTimeout(Math.max(1, budget.timeoutMs() / 1000));
            if (binds != null) {
                for (int i = 0; i < binds.size(); i++) {
                    ps.setObject(i + 1, binds.get(i));
                }
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
        } catch (SQLTimeoutException e) {
            throw ApiException.dataTimeout("数据查询超时，建议缩小时间范围");
        } catch (ApiException e) {
            throw e;
        } catch (Exception e) {
            log.error("query executor error, source={}", source, e);
            throw ApiException.dataUnavailable("数据查询失败: " + e.getMessage());
        }
        long elapsed = System.currentTimeMillis() - start;

        // 截断必须可见：多查 1 行检测，绝不静默截断
        boolean truncated = rows.size() > budget.maxRows();
        if (truncated) {
            rows = new ArrayList<>(rows.subList(0, budget.maxRows()));
        }
        slowQueryRecorder.recordIfSlow(source, sql, elapsed, rows.size(), budget.maxRows(), ctx);
        return new QueryOutcome(rows, columnCount, elapsed, truncated);
    }
}

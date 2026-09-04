package com.dst.v2xagent.copilot;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.agentscope.core.tool.Tool;
import io.agentscope.core.tool.ToolParam;
import lombok.extern.slf4j.Slf4j;
import org.springframework.jdbc.core.JdbcTemplate;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Pattern;

/**
 * Schema 探索工具集（原子工具，只给 CROSS 跨域专家）：
 * list_tables / describe_table / sample_rows。
 * 让模型能自己看「数据长什么样」，从「填表」走向「探索」。
 * 安全红线：只读（SHOW/DESCRIBE/SELECT 白名单）、标识符正则校验、sample 强制小 LIMIT。
 */
@Slf4j
public class SchemaTools {

    /** 库表/列名白名单：字母开头，仅字母数字下划线 */
    private static final Pattern IDENT = Pattern.compile("^[a-zA-Z_][a-zA-Z0-9_]{0,63}$");

    private final JdbcTemplate jdbc;
    private final ObjectMapper mapper = new ObjectMapper();

    public SchemaTools(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    /** 列出库中全部表名 + 注释（快速了解有什么数据域） */
    @Tool(name = "list_tables", description = "列出分析库的全部表名与表注释，用于了解有哪些数据可用")
    public String listTables() {
        try {
            List<Map<String, Object>> rows = jdbc.queryForList(
                    "SELECT table_name AS table_name, table_comment AS comment"
                            + " FROM information_schema.tables"
                            + " WHERE table_schema = DATABASE() ORDER BY table_name");
            Map<String, Object> out = new LinkedHashMap<>();
            out.put("status", "ok");
            out.put("tableCount", rows.size());
            out.put("tables", rows);
            out.put("hint", "下一步可用 describe_table 查看某张表的列结构，或 sample_rows 抽样看数据形态。"
                    + "常见表：basic_vehicle_info（车辆主数据）、adas_aggregation_simple_alarm_record（告警）、"
                    + "vehicle_mileage_data（里程）、tbox_fault_history（故障）。");
            return toJson(out);
        } catch (Exception e) {
            return err("list_tables 失败：" + e.getMessage());
        }
    }

    /** 查看某张表的列结构（列名/类型/注释） */
    @Tool(name = "describe_table", description = "查看指定表的列结构：列名、类型、注释（先 list_tables 拿表名）")
    public String describeTable(
            @ToolParam(name = "table", required = true, description = "表名（来自 list_tables 的结果）") String table) {
        String t = safeIdent(table);
        if (t == null) {
            return err("表名不合法（仅允许字母数字下划线）：" + table);
        }
        try {
            List<Map<String, Object>> rows = jdbc.queryForList(
                    "SELECT column_name AS col, data_type AS type, column_comment AS comment"
                            + " FROM information_schema.columns"
                            + " WHERE table_schema = DATABASE() AND table_name = '" + t.replace("'", "''") + "'"
                            + " ORDER BY ordinal_position");
            if (rows.isEmpty()) {
                return err("表不存在或无列信息：" + t + "（先调 list_tables 确认表名）");
            }
            Map<String, Object> out = new LinkedHashMap<>();
            out.put("status", "ok");
            out.put("table", t);
            out.put("columnCount", rows.size());
            out.put("columns", rows);
            return toJson(out);
        } catch (Exception e) {
            return err("describe_table 失败：" + e.getMessage());
        }
    }

    /** 抽样看数据形态：前 N 行全列 */
    @Tool(name = "sample_rows", description = "抽样查看某张表的数据（默认前 5 行），用于确认数据形态与字段含义")
    public String sampleRows(
            @ToolParam(name = "table", required = true, description = "表名") String table,
            @ToolParam(name = "limit", required = false, description = "抽样行数，默认 5，上限 20") Integer limit) {
        String t = safeIdent(table);
        if (t == null) {
            return err("表名不合法（仅允许字母数字下划线）：" + table);
        }
        int n = limit == null ? 5 : Math.min(20, Math.max(1, limit));
        try {
            // 先探行数（超过 100 万的表不给全表 count，快速近似即可走 information_schema）
            List<Map<String, Object>> rows = jdbc.queryForList(
                    "SELECT * FROM " + t + " LIMIT " + n);
            Map<String, Object> out = new LinkedHashMap<>();
            out.put("status", "ok");
            out.put("table", t);
            out.put("sampled", rows.size());
            out.put("columns", rows.isEmpty() ? List.of() : rows.get(0).keySet().stream().toList());
            out.put("rows", rows.stream().map(m -> {
                Map<String, Object> r = new LinkedHashMap<>();
                m.forEach((k, v) -> r.put(k, v == null ? null : String.valueOf(v)));
                return r;
            }).toList());
            out.put("hint", "抽样仅用于理解数据形态；正式统计请改用领域查询工具（计数/聚合走它们更准更快）。");
            return toJson(out);
        } catch (Exception e) {
            return err("sample_rows 失败：" + e.getMessage() + "（确认表存在，必要时先 describe_table）");
        }
    }

    /** 标识符校验：不合法返回 null（防 SQL 注入的第一道闸） */
    private static String safeIdent(String s) {
        if (s == null) {
            return null;
        }
        String t = s.trim();
        return IDENT.matcher(t).matches() ? t : null;
    }

    private String err(String msg) {
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("status", "error");
        out.put("message", msg);
        return toJson(out);
    }

    private String toJson(Object v) {
        try {
            return mapper.writeValueAsString(v);
        } catch (Exception e) {
            return String.valueOf(v);
        }
    }
}

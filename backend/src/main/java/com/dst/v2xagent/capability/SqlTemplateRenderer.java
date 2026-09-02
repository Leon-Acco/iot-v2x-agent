package com.dst.v2xagent.capability;

import com.dst.v2xagent.common.ApiException;

import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * SQL 模板渲染器（全局唯一参数展开入口，安全关键）
 *
 * 占位符规则：
 *  ${name}        标量 → 单个 ? 占位符；数组 → 展开为等量 ? 占位符
 *  ${name_empty}  数组为空的标记 → 字面量 1/0（服务端计算值，非用户输入，可安全内联）
 *  ${acl_fleet_ids} 权限谓词：由 PermissionContext 注入，模板不可省，模型无法传入
 *
 * 所有业务值一律走 PreparedStatement ? 绑定，绝不字符串拼接。
 */
public final class SqlTemplateRenderer {

    private SqlTemplateRenderer() {}

    private static final Pattern PLACEHOLDER = Pattern.compile("\\$\\{([a-zA-Z0-9_]+)}");
    private static final DateTimeFormatter TS_FMT = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss");

    /** 渲染结果：最终 SQL + 有序绑定值 */
    public record RenderedSql(String sql, List<Object> bindValues, String maskedSnapshot) {}

    /**
     * 渲染 SQL 模板。
     * @param template    SQL 模板
     * @param params      已完成校验/归一化/权限注入的参数（acl_* 必须已在其中）
     * @param maxItems    数组参数展开个数上限
     */
    public static RenderedSql render(String template, Map<String, Object> params, int maxItems) {
        StringBuilder sql = new StringBuilder();
        List<Object> binds = new ArrayList<>();
        // 预扫描带 ${key_empty} 旁路标记的参数：这些参数缺失/为空时 ${key} 渲染为 NULL（OR 短路不生效但语法合法）
        Set<String> bypassable = new HashSet<>();
        Matcher prescan = PLACEHOLDER.matcher(template);
        while (prescan.find()) {
            String k = prescan.group(1);
            if (k.endsWith("_empty")) bypassable.add(k.substring(0, k.length() - "_empty".length()));
        }

        Matcher m = PLACEHOLDER.matcher(template);
        while (m.find()) {
            String key = m.group(1);
            String replacement;
            if (key.endsWith("_empty")) {
                String arrayKey = key.substring(0, key.length() - "_empty".length());
                Object v = params.get(arrayKey);
                boolean empty = v == null || (v instanceof Collection<?> c && c.isEmpty());
                replacement = empty ? "1" : "0";
            } else {
                Object value = params.get(key);
                boolean missing = value == null || (value instanceof Collection<?> c && c.isEmpty());
                if (missing && bypassable.contains(key)) {
                    replacement = "NULL";
                } else if (missing) {
                    throw ApiException.paramInvalid("SQL 模板占位符缺少参数值：" + key);
                } else if (value instanceof Collection<?> coll) {
                    if (coll.size() > maxItems) {
                        throw ApiException.paramInvalid("数组参数 " + key + " 长度 " + coll.size() + " 超过上限 " + maxItems);
                    }
                    replacement = String.join(", ", java.util.Collections.nCopies(coll.size(), "?"));
                    binds.addAll(coll);
                } else {
                    replacement = "?";
                    binds.add(value);
                }
            }
            m.appendReplacement(sql, Matcher.quoteReplacement(replacement));
        }
        m.appendTail(sql);
        return new RenderedSql(sql.toString(), binds, maskedSnapshot(sql.toString(), binds));
    }

    /** 生成脱敏 SQL 快照（执行过程区展示）：绑定值内联展示，VIN/车牌截断 */
    private static String maskedSnapshot(String sql, List<Object> binds) {
        StringBuilder sb = new StringBuilder();
        int i = 0;
        for (String part : sql.split("\\?", -1)) {
            sb.append(part);
            if (i < binds.size()) {
                Object v = binds.get(i++);
                if (v instanceof LocalDateTime t) {
                    sb.append('\'').append(TS_FMT.format(t)).append('\'');
                } else if (v instanceof Number n) {
                    sb.append(n);
                } else {
                    String s = String.valueOf(v);
                    // 疑似 VIN（17 位）只保留后 6 位
                    if (s.length() == 17) s = "***" + s.substring(11);
                    sb.append('\'').append(s).append('\'');
                }
            }
        }
        return sb.toString();
    }
}

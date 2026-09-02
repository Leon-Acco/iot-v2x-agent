package com.dst.v2xagent.capability;

import com.dst.v2xagent.capability.model.CapabilityDefinition;
import com.dst.v2xagent.common.ApiException;

import java.util.HashSet;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * capability 注册静态校验（启动 / 后台发布时 fail-fast）
 * 规则来自后端设计文档 §6.5：权限谓词不可省、禁 SELECT *、必带 LIMIT、声明了分区键必须命中
 */
public final class CapabilityValidator {

    private CapabilityValidator() {}

    private static final Pattern PLACEHOLDER = Pattern.compile("\\$\\{([a-zA-Z0-9_]+)}");

    /**
     * 校验 capability 定义，不通过直接抛异常（宁可启动失败，不可带病上线）
     */
    public static void validate(CapabilityDefinition def) {
                // 编排模板引用型：不承载 SQL，跳过 SQL 模板检查（模板本身在 orchestration/*.yaml 另行校验）
        if ("orchestration".equals(def.getKind())) {
            return;
        }
if (def.getRowFilterPolicy() == null || def.getRowFilterPolicy().isBlank()) {
            throw new IllegalStateException("capability " + def.getId() + " 缺少 row_filter_policy，注册失败");
        }
        String sql = def.getSqlTemplate();
        if (sql == null || sql.isBlank()) {
            throw new IllegalStateException("capability " + def.getId() + " 缺少 SQL 模板");
        }
        String upper = sql.toUpperCase();

        // 1. 权限谓词必须存在：模板作者必须写出 acl 占位符，否则注册阶段直接 fail-fast
        String aclPlaceholder = switch (def.getRowFilterPolicy()) {
            case "by_fleet" -> "${acl_fleet_ids}";
            case "by_vin" -> "${acl_vins}";
            case "by_org" -> "${acl_org_ids}";
            default -> throw new IllegalStateException("capability " + def.getId() + " 未知 row_filter_policy: " + def.getRowFilterPolicy());
        };
        if (!sql.contains(aclPlaceholder)) {
            throw new IllegalStateException("capability " + def.getId() + " SQL 模板缺少权限占位符 " + aclPlaceholder + "（宁可报错，不可少一个过滤条件）");
        }

        // 2. 禁止 SELECT *：列必须与 returns.columns 一一对应
        if (upper.matches("(?s).*SELECT\\s+\\*.*")) {
            throw new IllegalStateException("capability " + def.getId() + " SQL 模板禁止 SELECT *");
        }

        // 3. 必须带 LIMIT（执行时还会强制重写为 min(模板值, max_rows)）
        if (!upper.contains("LIMIT")) {
            throw new IllegalStateException("capability " + def.getId() + " SQL 模板必须包含 LIMIT");
        }

        // 4. 声明了分区键的模板，WHERE 必须命中分区列
        if (def.getPartitionColumn() != null && !def.getPartitionColumn().isBlank()
                && !sql.contains(def.getPartitionColumn())) {
            throw new IllegalStateException("capability " + def.getId() + " SQL 模板未命中声明的分区键 " + def.getPartitionColumn());
        }

        // 5. 占位符白名单：除 acl_* 与 *_empty 外，必须对应已声明参数（或 daterange 派生的 time_from/time_to）
        Set<String> allowed = new HashSet<>();
        for (CapabilityDefinition.ParamDef p : def.getParams()) {
            allowed.add(p.getName());
            allowed.add(p.getName() + "_empty");
            if ("daterange".equals(p.getType())) {
                allowed.add("time_from");
                allowed.add("time_to");
            }
        }
        Matcher m = PLACEHOLDER.matcher(sql);
        while (m.find()) {
            String key = m.group(1);
            if (key.startsWith("acl_")) continue;
            if (!allowed.contains(key)) {
                throw new IllegalStateException("capability " + def.getId() + " SQL 模板包含未声明的占位符 ${" + key + "}");
            }
        }
    }
}

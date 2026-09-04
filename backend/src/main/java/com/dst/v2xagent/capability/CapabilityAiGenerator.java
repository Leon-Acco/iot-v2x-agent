package com.dst.v2xagent.capability;

import com.dst.v2xagent.capability.model.CapabilityDefinition;
import com.dst.v2xagent.common.ApiException;
import com.dst.v2xagent.llm.LlmProperties;
import com.dst.v2xagent.llm.PromptBuilder;
import com.dst.v2xagent.runtime.spi.StructuredModelClient;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.PropertyNamingStrategies;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * AI 能力生成器：用户描述 + 表结构 → capability 草稿（含 SQL 模板）
 * 真实模式走 StructuredModelClient 结构化输出，产物过 CapabilityValidator 静态校验（失败带原因重试 1 次）；
 * mock/无供应商模式走规则模板生成，保证开发环境可用。
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class CapabilityAiGenerator {

    private final LlmProperties props;
    private final ObjectProvider<StructuredModelClient> structuredClient;
    private final SchemaIntrospectionService schemaService;
    private final CapabilityRegistry registry;

    /** 模型产出为 snake_case，与 yaml/Java 模型同源 */
        /** 生成阶段超时：完整能力定义 JSON 输出较长，不复用抽取阶段的短超时 */
    private static final int GEN_TIMEOUT_MS = 60_000;

private final ObjectMapper snakeMapper = new ObjectMapper()
            .setPropertyNamingStrategy(PropertyNamingStrategies.SNAKE_CASE);

    /** 生成结果：草稿定义 + 是否 mock + 提示 */
    public record GenResult(CapabilityDefinition definition, boolean mock, String notice) {}

    /**
     * 生成能力草稿。
     * @param description 用户需求描述（自然语言）
     * @param tables      用户选定的数据表（至少 1 张）
     */
    public GenResult generate(String description, List<String> tables) {
        if (tables == null || tables.isEmpty()) {
            throw ApiException.paramInvalid("请至少选择 1 张数据表");
        }
        // 描述为空时走自动分析模式：AI 基于表字段/注释自主设计最有价值的能力
        boolean autoMode = description == null || description.isBlank();
        String effective = autoMode
                ? "自动分析表结构，设计该表最有价值的业务查询能力"
                : description.trim();
        String schemaText = schemaService.describeForPrompt(tables);
        StructuredModelClient client = "mock".equals(props.getActive()) ? null : structuredClient.getIfAvailable();
        if (client == null) {
            log.info("LLM 供应商不可用，降级为规则模式生成");
            return mockGenerate(effective, tables, autoMode);
        }
        List<String> existingIds = registry.listAll().stream().map(CapabilityDefinition::getId).toList();
        String basePrompt = PromptBuilder.genUserPrompt(effective, schemaText, existingIds);
        if (autoMode) {
            basePrompt += "\n\nThe user gave NO requirement. Analyze the table structure and comments, "
                    + "then design the single most valuable business query capability for this table.";
        }
        Exception last = null;
        for (int attempt = 0; attempt < 2; attempt++) {
            try {
                String prompt = last == null ? basePrompt
                        : basePrompt + "\n\nPrevious output failed validation: " + last.getMessage() + ". Fix and regenerate.";
                String json = client.generate(new StructuredModelClient.StructuredRequest(
                        PromptBuilder.GEN_SYSTEM, prompt, PromptBuilder.genOutputSchema(),
                        attempt == 0 ? 0.2 : 0, GEN_TIMEOUT_MS));
                CapabilityDefinition def = snakeMapper.readValue(json, CapabilityDefinition.class);
                normalize(def, tables);
                if (registry.get(def.getId()).isPresent()) {
                    throw new IllegalStateException("id already exists: " + def.getId());
                }
                CapabilityValidator.validate(def);
                return new GenResult(def, false, null);
            } catch (Exception e) {
                last = e;
                log.warn("capability AI 生成第 {} 次失败: {}", attempt + 1, e.getMessage());
            }
        }
        // LLM 超时/不可用时降级为规则模式（保证功能永远可用，降级信息随结果返回）
        log.warn("capability AI 生成走 LLM 失败，降级为规则模式: {}", last == null ? "unknown" : last.getMessage());
        GenResult fallback = mockGenerate(effective, tables, autoMode);
        return new GenResult(fallback.definition(), true,
                "LLM 不可用（" + (last == null ? "unknown" : last.getMessage())
                + "），已降级为规则模式生成，请人工复核 SQL 后提交审批");
    }

    /** 产物规一化：强制草稿态、默认值补齐（防模型越权直接上线） */
    private void normalize(CapabilityDefinition def, List<String> tables) {
        def.setKind("capability");
        def.setStatus("draft");
        def.setVersion(1);
        def.setReadonly(true);
        def.setOwner("AI");
        if (def.getSourceTables() == null || def.getSourceTables().isEmpty()) {
            def.setSourceTables(tables);
        }
        if (def.getAliases() == null) {
            def.setAliases(List.of());
        }
        if (def.getParams() == null) {
            def.setParams(List.of());
        }
        // scope 强制落到已授权集合：同源表能力继承 → 域映射 → 后备，保证草稿可直接试跑
        def.setScopes(List.of(resolveGrantedScope(def, tables)));
        if (def.getLimits() == null) {
            def.setLimits(new CapabilityDefinition.Limits());
        }
        if (def.getRowFilterPolicy() == null || def.getRowFilterPolicy().isBlank()) {
            def.setRowFilterPolicy("by_org");
        }
        if (def.getSampleQuestions() == null || def.getSampleQuestions().isEmpty()) {
            def.setSampleQuestions(List.of(def.getDescription() == null ? def.getDisplay() : def.getDescription()));
        }
    }

    /** mock 规则生成：按列名词法拼标准查询模板（开发联调/降级路径） */

    /** 域 → 已授权 scope 映射（与 AuthService.READ_SCOPES 对齐） */
    private static final java.util.Map<String, String> DOMAIN_SCOPE = java.util.Map.of(
            "online", "vehicle.status.read",
            "location", "vehicle.location.read",
            "mileage", "vehicle.mileage.read",
            "alarm", "vehicle.alarm.read",
            "fault", "vehicle.fault.read",
            "charge", "vehicle.charge.read",
            "trip", "vehicle.trip.read",
            "geofence", "vehicle.fence.read");

    /** 解析该能力应用的已授权 scope */
    private String resolveGrantedScope(CapabilityDefinition def, List<String> tables) {
        for (CapabilityDefinition existing : registry.listOnline()) {
            if (existing.getScopes() == null || existing.getScopes().isEmpty()) {
                continue;
            }
            for (String t : tables) {
                if (existing.getSourceTables() != null && existing.getSourceTables().contains(t)) {
                    return existing.getScopes().get(0);
                }
            }
        }
        String mapped = DOMAIN_SCOPE.get(def.getDomain() == null ? "" : def.getDomain());
        return mapped != null ? mapped : "vehicle.mileage.read";
    }

    /** 从表名猜域（mock 路径） */
    private String guessDomain(String table) {
        String t = table.toLowerCase();
        if (t.contains("alarm")) {
            return "alarm";
        }
        if (t.contains("fault")) {
            return "fault";
        }
        if (t.contains("charge")) {
            return "charge";
        }
        if (t.contains("trip")) {
            return "trip";
        }
        if (t.contains("mileage")) {
            return "mileage";
        }
        if (t.contains("fence")) {
            return "geofence";
        }
        if (t.contains("position") || t.contains("location")) {
            return "location";
        }
        if (t.contains("online") || t.contains("realtime") || t.contains("status")) {
            return "online";
        }
        return "custom";
    }

    private GenResult mockGenerate(String description, List<String> tables, boolean autoMode) {
        String t0 = tables.get(0);
        List<Map<String, Object>> cols = schemaService.listColumns(t0);
        String timeCol = null;
        String vinCol = null;
        for (Map<String, Object> c : cols) {
            String name = String.valueOf(c.get("column_name")).toLowerCase();
            if (timeCol == null && (name.contains("time") || name.contains("date"))) {
                timeCol = name;
            }
            // VIN 列偏好：vin_code > vin；car_no 是车牌不是 VIN（ACL 子查询用 vin_code 对不上会得 0 行）
            if (vinCol == null && ("vin_code".equals(name) || "vin".equals(name))) {
                vinCol = name;
            }
        }
        // 选列：vin/时间列优先，再补齐至 6 列
        List<Map<String, Object>> picked = new ArrayList<>();
        for (Map<String, Object> c : cols) {
            String name = String.valueOf(c.get("column_name"));
            if (name.equalsIgnoreCase(vinCol) || name.equalsIgnoreCase(timeCol)) {
                picked.add(c);
            }
        }
        for (Map<String, Object> c : cols) {
            if (picked.size() >= 6) {
                break;
            }
            if (!picked.contains(c)) {
                picked.add(c);
            }
        }
        StringBuilder select = new StringBuilder();
        List<CapabilityDefinition.ColumnDef> retCols = new ArrayList<>();
        for (Map<String, Object> c : picked) {
            String name = String.valueOf(c.get("column_name"));
            String type = String.valueOf(c.get("column_type")).toLowerCase();
            Object comment = c.get("column_comment");
            String display = comment != null && !String.valueOf(comment).isBlank()
                    ? String.valueOf(comment) : name;
            if (select.length() > 0) {
                select.append(", ");
            }
            select.append("t.").append(name);
            CapabilityDefinition.ColumnDef cd = new CapabilityDefinition.ColumnDef();
            cd.setName(name);
            cd.setDisplay(display);
            boolean numeric = type.contains("int") || type.contains("double") || type.contains("float")
                    || type.contains("decimal");
            cd.setSemantic(numeric && !name.equalsIgnoreCase(vinCol) ? "metric" : "category");
            retCols.add(cd);
        }
        StringBuilder sql = new StringBuilder("SELECT ").append(select).append("\nFROM ").append(t0).append(" t\nWHERE 1 = 1\n");
        List<CapabilityDefinition.ParamDef> params = new ArrayList<>();
        if (timeCol != null) {
            sql.append("  AND t.").append(timeCol).append(" >= ${time_from}\n");
            sql.append("  AND t.").append(timeCol).append(" < ${time_to}\n");
            CapabilityDefinition.ParamDef p = new CapabilityDefinition.ParamDef();
            p.setName("time_range");
            p.setType("daterange");
            p.setRequired(false);
            p.setDescription("时间范围");
            p.setMaxSpanDays(90);
            params.add(p);
        }
        if (vinCol != null) {
            sql.append("  AND (${vin_list_empty} OR t.").append(vinCol).append(" IN (${vin_list}))\n");
            CapabilityDefinition.ParamDef v = new CapabilityDefinition.ParamDef();
            v.setName("vehicle");
            v.setType("string");
            v.setRequired(false);
            v.setDescription("车牌号或 VIN");
            params.add(v);
            CapabilityDefinition.ParamDef vl = new CapabilityDefinition.ParamDef();
            vl.setName("vin_list");
            vl.setType("array<string>");
            vl.setRequired(false);
            vl.setMaxItems(50);
            params.add(vl);
        }
        String aclCol = vinCol != null ? vinCol : "vin_code";
        sql.append("  AND t.").append(aclCol)
                .append(" IN (SELECT vin_code FROM basic_vehicle_info WHERE org_name IN (${acl_org_ids}))\n");
        if (timeCol != null) {
            sql.append("ORDER BY t.").append(timeCol).append(" DESC\n");
        }
        sql.append("LIMIT 500");

        CapabilityDefinition def = new CapabilityDefinition();
        // 尾随机数保证 id 唯一（同表反复生成不撞车）
        String rawId = "ai_" + t0 + "_" + Integer.toHexString((description + t0).hashCode()).replace("-", "0")
                + "_" + Integer.toHexString((int) (System.nanoTime() & 0xffff));
        def.setId(rawId.length() > 64 ? rawId.substring(0, 64) : rawId);
        String tableComment = String.valueOf(schemaService.listTables().stream()
                .filter(row -> t0.equals(String.valueOf(row.get("table_name"))))
                .map(row -> row.get("table_comment")).findFirst().orElse(""));
        boolean descBlank = autoMode;
        String display;
        if (descBlank && tableComment != null && !tableComment.isBlank() && !"null".equals(tableComment)) {
            display = tableComment.length() > 12 ? tableComment.substring(0, 12) : tableComment;
        } else {
            display = descBlank ? (t0 + " 查询") : (description.length() > 12 ? description.substring(0, 12) : description);
        }
        if (descBlank) {
            description = tableComment != null && !tableComment.isBlank() && !"null".equals(tableComment)
                    ? tableComment : (t0 + " 数据查询");
        }
        def.setDisplay(display);
        def.setDescription(description);
        def.setAliases(List.of(display));
        def.setDomain(guessDomain(t0));
        def.setParams(params);
        CapabilityDefinition.ReturnsDef returns = new CapabilityDefinition.ReturnsDef();
        returns.setShape("table");
        returns.setColumns(retCols);
        def.setReturns(returns);
        def.setChartHint("table");
        def.setSourceTables(tables);
        def.setSqlTemplate(sql.toString());
        normalize(def, tables);
        CapabilityValidator.validate(def);
        return new GenResult(def, true,
                "当前为规则模式生成（mock），请人工复核 SQL 后提交审批");
    }
}

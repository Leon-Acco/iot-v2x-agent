package com.dst.v2xagent.taskcard;

import com.dst.v2xagent.capability.CapabilityExecutor;
import com.dst.v2xagent.capability.CapabilityRegistry;
import com.dst.v2xagent.capability.ParamResolver;
import com.dst.v2xagent.capability.model.CapabilityDefinition;
import com.dst.v2xagent.capability.model.TableResult;
import com.dst.v2xagent.common.PermissionContext;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.openpdf.text.Document;
import org.openpdf.text.Font;
import org.openpdf.text.PageSize;
import org.openpdf.text.Paragraph;
import org.openpdf.text.Phrase;
import org.openpdf.text.pdf.BaseFont;
import org.openpdf.text.pdf.PdfPCell;
import org.openpdf.text.pdf.PdfPTable;
import org.openpdf.text.pdf.PdfWriter;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

import java.io.FileOutputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.ZonedDateTime;
import java.time.format.DateTimeFormatter;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * 任务卡服务（R1 草稿级）：导出创建时固化快照，生成 PDF 并入库。
 * 设计文档 §11.3：capabilityVersion/参数/权限指纹/数据水位 不可变。
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class TaskCardService {

    private final CapabilityRegistry capabilityRegistry;
    private final ParamResolver paramResolver;
    private final CapabilityExecutor capabilityExecutor;
    private final JdbcTemplate controlJdbcTemplate;
    /** Spring 管理的 ObjectMapper（已注册 jsr310，支持 LocalDateTime 序列化） */
    private final ObjectMapper mapper;

    @Value("${agent.task-card.dir:./data/task-cards}")
    private String storeDir;

    /** 创建任务卡：重新执行固化水位 → 快照入库 → PDF 落盘 */
    public Map<String, Object> create(PermissionContext ctx, CreateRequest req) throws Exception {
        CapabilityDefinition def = resolveDefinition(req.capabilityId());
        // copilot 参数键与 capability 参数名不同源（time_range_display 等），按目标参数集白名单归一
        Map<String, Object> params = sanitizeParams(def, req.params());
        return doCreate(ctx, new CreateRequest(req.title(), req.capabilityId(),
                params, req.runId(), req.traceId(), req.conclusion()), def);
    }

    /**
     * 参数归一：别名映射（time_range_display -> time_range）+ 未知键剔除
     * （ParamResolver 对未知键严格报错，copilot 透传的 hours/limit 等会拦截）。
     */
    private Map<String, Object> sanitizeParams(CapabilityDefinition def, Map<String, Object> raw) {
        if (raw == null || raw.isEmpty()) {
            return Map.of();
        }
        java.util.Set<String> known = new java.util.HashSet<>();
        for (CapabilityDefinition.ParamDef p : def.getParams()) {
            if (p.getName() != null) {
                known.add(p.getName());
            }
        }
        Map<String, Object> work = new java.util.LinkedHashMap<>(raw);
        Object tr = work.remove("time_range_display");
        if (tr != null) {
            work.putIfAbsent("time_range", tr);
        }
        Map<String, Object> out = new java.util.LinkedHashMap<>();
        for (Map.Entry<String, Object> e : work.entrySet()) {
            if (known.contains(e.getKey())) {
                out.put(e.getKey(), e.getValue());
            }
        }
        return out;
    }

    /**
     * 能力定义解析：registry 直查；查不到时按 copilot 工具目录映射
     * （对话流工具是 copilot 内部体系如 real_alarm_count/中文名，与 capability id 不同源）。
     */
    private CapabilityDefinition resolveDefinition(String capabilityId) throws Exception {
        try {
            return capabilityRegistry.require(capabilityId);
        } catch (Exception notFound) {
            String mapped = com.dst.v2xagent.copilot.CopilotToolCatalog.registryIdOf(capabilityId);
            if (mapped != null) {
                return capabilityRegistry.require(mapped);
            }
            throw notFound;
        }
    }

    private Map<String, Object> doCreate(PermissionContext ctx, CreateRequest req,
                                         CapabilityDefinition def) throws Exception {
        ParamResolver.ResolvedParams resolved = paramResolver.resolve(def, req.params(), ctx, ZonedDateTime.now());
        TableResult table = capabilityExecutor.execute(def, resolved, ctx);

        // 不可变快照：前 50 行 + 结论 + 时效 + 权限指纹
        Map<String, Object> snapshot = new LinkedHashMap<>();
        snapshot.put("capabilityId", def.getId());
        snapshot.put("capabilityVersion", def.getVersion());
        snapshot.put("params", resolved.bindParams());
        snapshot.put("permissionFingerprint", ctx.fingerprint());
        snapshot.put("columns", table.columns());
        snapshot.put("rows", table.rows().stream().limit(50).map(r ->
                java.util.Arrays.stream(r).map(c -> c == null ? null : c.toString()).toList()).toList());
        snapshot.put("rowCount", table.rowCount());
        snapshot.put("truncated", table.truncated());
        snapshot.put("freshness", Map.of(
                "dataAsOf", table.freshness().dataAsOf(),
                "policyType", table.freshness().policyType(),
                "expectedDelayMin", table.freshness().expectedDelayMin()));
        snapshot.put("conclusion", req.conclusion() == null ? "" : req.conclusion());
        snapshot.put("createdAt", ZonedDateTime.now().format(DateTimeFormatter.ISO_OFFSET_DATE_TIME));

        String fileName = "tc-" + UUID.randomUUID().toString().replace("-", "") + ".pdf";
        Path dir = Path.of(storeDir);
        Files.createDirectories(dir);
        Path pdf = dir.resolve(fileName);
        renderPdf(pdf, req.title() == null ? def.getDisplay() : req.title(),
                def, table, snapshot, ctx, req.traceId());

        controlJdbcTemplate.update(
                "INSERT INTO task_card (tenant_id, run_id, trace_id, title, capability_id, capability_version,"
                + " params_json, snapshot_json, permission_fingerprint, file_path, created_by)"
                + " VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)",
                ctx.tenantId(), req.runId(), req.traceId(),
                req.title() == null ? def.getDisplay() : req.title(),
                def.getId(), def.getVersion(),
                mapper.writeValueAsString(resolved.bindParams()),
                mapper.writeValueAsString(snapshot),
                ctx.fingerprint(), pdf.toAbsolutePath().toString(), ctx.username());

        Long id = controlJdbcTemplate.queryForObject(
                "SELECT id FROM task_card WHERE tenant_id = ? AND created_by = ? ORDER BY id DESC LIMIT 1",
                Long.class, ctx.tenantId(), ctx.username());
        log.info("任务卡已生成: id={} by={}", id, ctx.username());
        return Map.of("id", id == null ? -1 : id, "title", req.title() == null ? def.getDisplay() : req.title());
    }

    public record CreateRequest(String title, String capabilityId, Map<String, Object> params,
                                String runId, String traceId, String conclusion) {}

    /** 列出当前用户创建的任务卡（最近 50 条） */
    public java.util.List<java.util.Map<String, Object>> listMine(PermissionContext ctx) {
        return controlJdbcTemplate.queryForList(
                "SELECT id, title, capability_id, run_id, trace_id, created_at, status"
                + " FROM task_card WHERE tenant_id = ? AND created_by = ? AND status = 'READY'"
                + " ORDER BY id DESC LIMIT 50",
                ctx.tenantId(), ctx.username());
    }

    /** 下载校验：状态 + 租户 + 本人（管理员可读全部） */
    public Map<String, Object> loadForDownload(PermissionContext ctx, long id) {
        List<Map<String, Object>> rows = controlJdbcTemplate.queryForList(
                "SELECT file_path, status, created_by, title FROM task_card WHERE tenant_id = ? AND id = ?",
                ctx.tenantId(), id);
        if (rows.isEmpty()) {
            throw com.dst.v2xagent.common.ApiException.paramInvalid("任务卡不存在: " + id);
        }
        Map<String, Object> row = rows.get(0);
        if (!"READY".equals(row.get("status"))) {
            throw com.dst.v2xagent.common.ApiException.scopeDenied("任务卡已失效");
        }
        if (!ctx.isAdmin() && !ctx.username().equals(row.get("created_by"))) {
            throw com.dst.v2xagent.common.ApiException.scopeDenied("只能下载自己创建的任务卡");
        }
        return row;
    }

    /** PDF 渲染：CJK 内置字体，标题/元信息/表格前 20 行/结论/水印 */
    private void renderPdf(Path pdf, String title, CapabilityDefinition def, TableResult table,
                           Map<String, Object> snapshot, PermissionContext ctx, String traceId) throws Exception {
        BaseFont cjk = BaseFont.createFont("STSong-Light", "UniGB-UCS2-H", BaseFont.NOT_EMBEDDED);
        Font titleFont = new Font(cjk, 16, Font.BOLD);
        Font metaFont = new Font(cjk, 9);
        Font headFont = new Font(cjk, 9, Font.BOLD);
        Font cellFont = new Font(cjk, 8);

        Document doc = new Document(PageSize.A4.rotate());
        PdfWriter.getInstance(doc, new FileOutputStream(pdf.toFile()));
        doc.open();
        doc.add(new Paragraph(title, titleFont));
        doc.add(new Paragraph(" ", metaFont));
        @SuppressWarnings("unchecked")
        Map<String, Object> freshness = (Map<String, Object>) snapshot.get("freshness");
        doc.add(new Paragraph(
                "能力：" + def.getDisplay() + " (v" + def.getVersion() + ")"
                + "    数据水位：" + freshness.get("dataAsOf")
                + "    traceId：" + (traceId == null ? "-" : traceId), metaFont));
        doc.add(new Paragraph(
                "权限指纹：" + ctx.fingerprint()
                + "    生成人：" + ctx.username()
                + "    生成时间：" + snapshot.get("createdAt"), metaFont));
        doc.add(new Paragraph(" ", metaFont));

        int colCount = Math.min(6, table.columns().size());
        PdfPTable pdfTable = new PdfPTable(colCount);
        pdfTable.setWidthPercentage(100);
        for (int c = 0; c < colCount; c++) {
            var col = table.columns().get(c);
            pdfTable.addCell(new PdfPCell(new Phrase(
                    col.getDisplay() + (col.getUnit() == null ? "" : "(" + col.getUnit() + ")"), headFont)));
        }
        int rows = Math.min(20, table.rows().size());
        for (int i = 0; i < rows; i++) {
            for (int c = 0; c < colCount; c++) {
                Object v = table.rows().get(i)[c];
                pdfTable.addCell(new PdfPCell(new Phrase(v == null ? "" : v.toString(), cellFont)));
            }
        }
        doc.add(pdfTable);
        doc.add(new Paragraph(
                "共 " + table.rowCount() + " 行"
                + (table.truncated() ? "（结果已截断，仅展示前 20 行）" : "（仅展示前 20 行）"),
                metaFont));

        String conclusion = String.valueOf(snapshot.get("conclusion"));
        if (!conclusion.isBlank()) {
            doc.add(new Paragraph(" ", metaFont));
            doc.add(new Paragraph("结论：", headFont));
            doc.add(new Paragraph(conclusion, metaFont));
        }
        doc.add(new Paragraph(" ", metaFont));
        doc.add(new Paragraph(
                "本任务卡为 R1 草稿级导出，数据以导出时水位为准；仅供内部使用。",
                metaFont));
        doc.close();
    }
}

package com.dst.v2xagent.task;

import com.dst.v2xagent.common.ApiException;
import com.dst.v2xagent.common.PermissionContext;
import com.dst.v2xagent.taskcard.PdfSupport;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.openpdf.text.Document;
import org.openpdf.text.PageSize;
import org.openpdf.text.Paragraph;
import org.openpdf.text.pdf.BaseFont;
import org.openpdf.text.pdf.PdfPTable;
import org.openpdf.text.pdf.PdfWriter;
import org.springframework.stereotype.Service;

import java.io.ByteArrayOutputStream;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * 任务执行记录 PDF 导出：单次 run 的「元信息 + 分析报告 + 各步骤数据表」排版为 A4。
 * 与会话导出同款范式：内存生成 byte[] 直接下载，不落盘、不入库（一次性产物）。
 * 合并旧任务卡（taskcard）后，任务中心的 PDF 导出能力由本类承接。
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class TaskRunExportService {

    private final TaskService taskService;

    /** 导出一次执行记录（权限校验走 getRun 内含的归属检查） */
    public byte[] export(PermissionContext ctx, long runId) {
        Map<String, Object> run = taskService.getRun(ctx, runId);
        try (ByteArrayOutputStream out = new ByteArrayOutputStream()) {
            BaseFont cjk = PdfSupport.cjkFont();
            Document doc = new Document(PageSize.A4);
            PdfWriter.getInstance(doc, out);
            doc.open();

            // 标题 + 元信息
            doc.add(new Paragraph(String.valueOf(run.getOrDefault("title", "任务执行记录")),
                    PdfSupport.titleFont(cjk)));
            String trigger = "cron".equals(run.get("triggerType")) ? "定时执行" : "手动执行";
            doc.add(new Paragraph("执行方式：" + trigger + " · 操作人：" + run.get("triggeredBy")
                    + " · 状态：" + run.get("status")
                    + " · 耗时：" + run.get("elapsedMs") + "ms"
                    + " · 开始：" + run.get("startedAt"), PdfSupport.metaFont(cjk)));
            doc.add(new Paragraph(" "));

            // 分析报告（Markdown 记号去除，按行输出）
            Object conclusion = run.get("conclusion");
            if (conclusion != null && !String.valueOf(conclusion).isBlank()) {
                doc.add(new Paragraph("分析报告", PdfSupport.headFont(cjk)));
                doc.add(new Paragraph(" "));
                for (String line : String.valueOf(conclusion).split("\n")) {
                    String text = line.replace("**", "").trim();
                    if (!text.isEmpty()) {
                        doc.add(new Paragraph(text, PdfSupport.cellFont(cjk)));
                    }
                }
                doc.add(new Paragraph(" "));
            }

            // 各步骤数据表（列头用 display，行数上限 20 与前端表格一致）
            if (run.get("steps") instanceof List<?> steps) {
                for (Object s : steps) {
                    if (!(s instanceof Map<?, ?> step)) {
                        continue;
                    }
                    doc.add(new Paragraph("步骤 " + step.get("seq") + " · " + step.get("displayName")
                            + "（" + step.get("status") + "）", PdfSupport.headFont(cjk)));
                    if (step.get("result") instanceof Map<?, ?> result
                            && result.get("columns") instanceof List<?> cols) {
                        List<String> headers = new ArrayList<>();
                        for (Object c : cols) {
                            headers.add(c instanceof Map<?, ?> m && m.get("display") != null
                                    ? String.valueOf(m.get("display")) : String.valueOf(c));
                        }
                        List<List<Object>> rows = new ArrayList<>();
                        if (result.get("rows") instanceof List<?> rawRows) {
                            for (Object r : rawRows) {
                                if (r instanceof List<?> row) {
                                    rows.add(new ArrayList<>(row));
                                }
                            }
                        }
                        PdfPTable table = PdfSupport.dataTable(headers, rows, 8, 20,
                                PdfSupport.headFont(cjk), PdfSupport.cellFont(cjk));
                        doc.add(table);
                    } else if (step.get("error") != null) {
                        doc.add(new Paragraph("失败原因：" + step.get("error"), PdfSupport.cellFont(cjk)));
                    }
                    doc.add(new Paragraph(" "));
                }
            }

            doc.close();
            return out.toByteArray();
        } catch (Exception e) {
            log.error("task run export failed: runId={}", runId, e);
            throw new ApiException("PDF_EXPORT_FAILED", "export", false, "任务执行记录导出失败");
        }
    }
}

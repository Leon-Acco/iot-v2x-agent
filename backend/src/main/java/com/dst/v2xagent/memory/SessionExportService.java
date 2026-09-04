package com.dst.v2xagent.memory;

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
import org.springframework.stereotype.Component;

import java.io.ByteArrayOutputStream;
import java.time.ZonedDateTime;
import java.time.format.DateTimeFormatter;
import java.util.List;
import java.util.Map;

/**
 * 会话级 PDF 导出：整个会话多轮对话（用户问题 / AI 结论 / 数据表格 / 图表说明）。
 * 与任务卡不同：内存生成 byte[] 直接下载，不落盘、不入库（一次性产物）。
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class SessionExportService {

    private final SessionStore sessionStore;

    /** 导出会话为 PDF 字节流（A4 纵向多页），校验租户 + 本人（管理员放行） */
    public byte[] export(PermissionContext ctx, String sessionId) {
        Map<String, Object> session = sessionStore.findSession(ctx.tenantId(), sessionId,
                ctx.isAdmin() ? null : ctx.userId());
        if (session == null) {
            throw ApiException.paramInvalid("会话不存在或无权访问: " + sessionId);
        }
        List<Map<String, Object>> messages = sessionStore.listMessages(ctx.tenantId(), sessionId);
        try {
            BaseFont cjk = PdfSupport.cjkFont();
            Document doc = new Document(PageSize.A4);
            ByteArrayOutputStream out = new ByteArrayOutputStream();
            PdfWriter.getInstance(doc, out);
            doc.open();

            String title = session.get("title") == null || String.valueOf(session.get("title")).isBlank()
                    ? "会话记录" : String.valueOf(session.get("title"));
            doc.add(new Paragraph(title, PdfSupport.titleFont(cjk)));
            doc.add(new Paragraph(" ", PdfSupport.metaFont(cjk)));
            doc.add(new Paragraph(
                    "导出时间：" + ZonedDateTime.now().format(DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm"))
                            + "    操作人：" + ctx.username()
                            + "    轮次：" + session.get("turnCount"), PdfSupport.metaFont(cjk)));
            doc.add(new Paragraph(" ", PdfSupport.metaFont(cjk)));

            for (Map<String, Object> m : messages) {
                String role = String.valueOf(m.get("role"));
                String content = m.get("content") == null ? "" : String.valueOf(m.get("content"));
                if ("user".equals(role)) {
                    doc.add(new Paragraph("用户", PdfSupport.headFont(cjk)));
                    doc.add(new Paragraph(content, PdfSupport.metaFont(cjk)));
                } else {
                    doc.add(new Paragraph(" ", PdfSupport.metaFont(cjk)));
                    doc.add(new Paragraph("助手", PdfSupport.headFont(cjk)));
                    if (!content.isBlank()) {
                        doc.add(new Paragraph(content, PdfSupport.metaFont(cjk)));
                    }
                    appendPayload(doc, m.get("payload"), cjk);
                }
            }
            doc.add(new Paragraph(" ", PdfSupport.metaFont(cjk)));
            doc.add(new Paragraph("本文件由运营工作台导出，内容为会话记录快照；仅供内部使用。",
                    PdfSupport.metaFont(cjk)));
            doc.close();
            return out.toByteArray();
        } catch (ApiException e) {
            throw e;
        } catch (Exception e) {
            log.error("session export failed: sessionId={}", sessionId, e);
            throw new ApiException("PDF_EXPORT_FAILED", "export", false, "会话导出失败");
        }
    }

    /** 追加帧快照内容：数据表格（前 10 行 6 列）+ 图表说明列表 */
    @SuppressWarnings("unchecked")
    private void appendPayload(Document doc, Object payloadObj, BaseFont cjk) throws Exception {
        if (!(payloadObj instanceof Map<?, ?> payload)) {
            return;
        }
        Object result = payload.get("result");
        if (result instanceof Map<?, ?> table && table.get("rows") instanceof List<?> rows && !rows.isEmpty()) {
            doc.add(new Paragraph(" ", PdfSupport.metaFont(cjk)));
            PdfPTable pdfTable = PdfSupport.payloadTable((Map<String, Object>) table, 6, 10,
                    PdfSupport.headFont(cjk), PdfSupport.cellFont(cjk));
            doc.add(pdfTable);
            Object rowCount = table.get("rowCount");
            doc.add(new Paragraph("共 " + rowCount + " 行（仅展示前 10 行）", PdfSupport.metaFont(cjk)));
        }
        if (payload.get("visualizations") instanceof List<?> vizs && !vizs.isEmpty()) {
            StringBuilder sb = new StringBuilder();
            for (Object v : vizs) {
                if (v instanceof Map<?, ?> ui) {
                    sb.append("- ").append(ui.get("title"));
                    if (ui.get("caption") != null) {
                        sb.append("：").append(ui.get("caption"));
                    }
                    sb.append("\n");
                }
            }
            if (!sb.isEmpty()) {
                doc.add(new Paragraph(" ", PdfSupport.metaFont(cjk)));
                doc.add(new Paragraph("图表：", PdfSupport.headFont(cjk)));
                doc.add(new Paragraph(sb.toString(), PdfSupport.metaFont(cjk)));
            }
        }
    }
}

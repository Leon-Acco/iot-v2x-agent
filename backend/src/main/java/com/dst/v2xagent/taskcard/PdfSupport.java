package com.dst.v2xagent.taskcard;

import org.openpdf.text.Font;
import org.openpdf.text.Phrase;
import org.openpdf.text.pdf.BaseFont;
import org.openpdf.text.pdf.PdfPCell;
import org.openpdf.text.pdf.PdfPTable;

import java.util.List;
import java.util.Map;

/**
 * PDF 公共支撑件：CJK 字体、字体套装、表格绘制。
 * 任务卡 PDF 与会话导出 PDF 共用（TaskCardService / SessionExportService）。
 */
public final class PdfSupport {

    private PdfSupport() {}

    /** CJK 内置字体（STSong-Light + UniGB-UCS2-H，不嵌入文件） */
    public static BaseFont cjkFont() throws Exception {
        return BaseFont.createFont("STSong-Light", "UniGB-UCS2-H", BaseFont.NOT_EMBEDDED);
    }

    /** 标题字体（16pt 加粗） */
    public static Font titleFont(BaseFont cjk) {
        return new Font(cjk, 16, Font.BOLD);
    }

    /** 元信息字体（9pt） */
    public static Font metaFont(BaseFont cjk) {
        return new Font(cjk, 9);
    }

    /** 表头字体（9pt 加粗） */
    public static Font headFont(BaseFont cjk) {
        return new Font(cjk, 9, Font.BOLD);
    }

    /** 单元格字体（8pt） */
    public static Font cellFont(BaseFont cjk) {
        return new Font(cjk, 8);
    }

    /**
     * 构建数据表（通用版）：表头字符串列表 + 行数据（Object toString）。
     * 列数上限 maxCols、行数上限 maxRows。
     */
    public static PdfPTable dataTable(List<String> headers, List<List<Object>> rows,
                                      int maxCols, int maxRows, Font head, Font cell) {
        int colCount = Math.min(maxCols, headers.size());
        PdfPTable table = new PdfPTable(colCount);
        table.setWidthPercentage(100);
        for (int c = 0; c < colCount; c++) {
            table.addCell(new PdfPCell(new Phrase(headers.get(c), head)));
        }
        int rowCount = Math.min(maxRows, rows == null ? 0 : rows.size());
        for (int i = 0; i < rowCount; i++) {
            List<Object> row = rows.get(i);
            for (int c = 0; c < colCount; c++) {
                Object v = c < row.size() ? row.get(c) : null;
                table.addCell(new PdfPCell(new Phrase(v == null ? "" : String.valueOf(v), cell)));
            }
        }
        return table;
    }

    /**
     * 构建数据表（payload 快照版）：列定义为 payload_json 反序列化后的 Map 列表
     * （含 display/unit 字段），行数据为 List&lt;List&lt;Object&gt;&gt;。
     */
    @SuppressWarnings("unchecked")
    public static PdfPTable payloadTable(Map<String, Object> tablePayload,
                                         int maxCols, int maxRows, Font head, Font cell) {
        List<Object> colDefs = (List<Object>) tablePayload.get("columns");
        List<Object> rowList = (List<Object>) tablePayload.get("rows");
        List<String> headers = new java.util.ArrayList<>();
        if (colDefs != null) {
            for (Object cd : colDefs) {
                String display;
                String unit = null;
                if (cd instanceof Map<?, ?> m) {
                    display = m.get("display") != null ? String.valueOf(m.get("display"))
                            : String.valueOf(m.get("name"));
                    unit = m.get("unit") == null ? null : String.valueOf(m.get("unit"));
                } else {
                    display = String.valueOf(cd);
                }
                headers.add(unit == null || "null".equals(unit) ? display : display + "(" + unit + ")");
            }
        }
        List<List<Object>> rows = new java.util.ArrayList<>();
        if (rowList != null) {
            for (Object r : rowList) {
                if (r instanceof List<?> list) {
                    rows.add(new java.util.ArrayList<>(list));
                }
            }
        }
        return dataTable(headers, rows, maxCols, maxRows, head, cell);
    }
}

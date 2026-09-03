package com.dst.v2xagent.copilot;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * 时间范围解析：把「近 7 天 / 昨天 / 上周」等口语化范围解析为绝对区间。
 * 红线：模拟库数据是静态的，所以相对范围锡定到「数据基准日」（库内最大日期），
 * 而不是服务器当天，避免演示时间推移后查不到数据。
 */
public final class TimeRanges {

    private TimeRanges() {}

    /** 解析结果：[from, to) 半开区间 + 展示文本 */
    public record Range(LocalDateTime from, LocalDateTime to, String display) {}

    private static final DateTimeFormatter DT = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss");

    /** 默认范围：近 7 天 */
    public static Range defaultRange(LocalDate baseDate) {
        return parse(null, baseDate);
    }

    /**
     * 解析口语化时间范围。
     * @param text     原始文本（可空，默认近 7 天）
     * @param baseDate 数据基准日（库内最大日期）
     */
    public static Range parse(String text, LocalDate baseDate) {
        String t = text == null ? "" : text.trim();
        LocalDate to = baseDate;
        LocalDate from;
        String display;
        if (t.isEmpty() || t.contains("近7") || t.contains("七天") || t.contains("一周")) {
            from = to.minusDays(6);
            display = "近 7 天";
        } else if (t.contains("今天") || t.contains("今日")) {
            from = to;
            display = "今天";
        } else if (t.contains("昨天") || t.contains("昨日")) {
            from = to.minusDays(1);
            to = to.minusDays(1);
            display = "昨天";
        } else if (t.contains("前天")) {
            from = to.minusDays(2);
            to = to.minusDays(2);
            display = "前天";
        } else if (t.contains("近30") || t.contains("一个月") || t.contains("近一个月")) {
            from = to.minusDays(29);
            display = "近 30 天";
        } else if (t.contains("近14") || t.contains("两周")) {
            from = to.minusDays(13);
            display = "近 14 天";
        } else if (t.contains("上周")) {
            LocalDate weekEnd = to.minusDays(to.getDayOfWeek().getValue());
            from = weekEnd.minusDays(6);
            to = weekEnd;
            display = "上周";
        } else if (t.contains("本周") || t.contains("这周")) {
            from = to.minusDays(to.getDayOfWeek().getValue() - 1);
            display = "本周";
        } else if (t.contains("近90") || t.contains("三个月")) {
            from = to.minusDays(89);
            display = "近 90 天";
        } else {
            from = to.minusDays(6);
            display = "近 7 天";
        }
        return new Range(from.atStartOfDay(), to.plusDays(1).atStartOfDay(),
                display + "（" + from + " ~ " + to + "）");
    }

    /** 转 SQL 参数格式 */
    public static Map<String, String> bind(Range r) {
        Map<String, String> m = new LinkedHashMap<>();
        m.put("from", r.from().format(DT));
        m.put("to", r.to().format(DT));
        return m;
    }
}

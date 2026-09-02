package com.dst.v2xagent.common;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.time.format.DateTimeFormatter;
import java.time.temporal.TemporalAdjusters;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 时间表达式归一化（准确率的第一杀手）
 * 自然语言时间不交给模型算绝对时间：模型只输出表达式，这里解析为绝对区间。
 * 所有解析结果必须通过 TOOL_CALL_ARGS 回显给用户。
 */
public final class TimeExpressionResolver {

    private TimeExpressionResolver() {}

    private static final ZoneId DEFAULT_ZONE = ZoneId.of("Asia/Shanghai");
    private static final DateTimeFormatter FMT = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss");

    /** 归一化后的绝对时间区间 [from, to) */
    public record TimeRange(LocalDateTime from, LocalDateTime to, String note) {
        public String fromText() { return from.format(FMT); }
        public String toText() { return to.format(FMT); }
        /** 回显文案，如「08-15 00:00 ~ 08-21 15:51」 */
        public String display() {
            DateTimeFormatter shortFmt = DateTimeFormatter.ofPattern("MM-dd HH:mm");
            return from.format(shortFmt) + " ~ " + to.format(shortFmt);
        }
    }

    private static final Pattern RECENT_DAYS = Pattern.compile("近\\s*(\\d+)\\s*天");
    private static final Pattern PAST_DAYS = Pattern.compile("过去\\s*(\\d+)\\s*天");
    private static final Pattern RECENT_HOURS = Pattern.compile("近\\s*(\\d+)\\s*(?:个)?小时");
    private static final Pattern EXPLICIT_RANGE = Pattern.compile(
            "(\\d{4})[-/年](\\d{1,2})[-/月](\\d{1,2})[日号]?\\s*(?:到|至|~|-)\\s*(\\d{4})[-/年](\\d{1,2})[-/月](\\d{1,2})[日号]?");

    /**
     * 解析自然语言时间表达式为绝对区间。
     * @param expr  模型输出的时间表达式（如「近7天」「昨天」）
     * @param anchor 锚点时间（请求时刻），测试时可注入
     */
    public static TimeRange resolve(String expr, ZonedDateTime anchor, ZoneId zone) {
        if (expr == null || expr.isBlank()) {
            throw ApiException.paramInvalid("缺少时间范围");
        }
        String e = expr.trim();
        ZonedDateTime now = anchor.withZoneSameInstant(zone);
        LocalDate today = now.toLocalDate();

        Matcher m;
        if ((m = EXPLICIT_RANGE.matcher(e)).find()) {
            LocalDate from = LocalDate.of(Integer.parseInt(m.group(1)), Integer.parseInt(m.group(2)), Integer.parseInt(m.group(3)));
            LocalDate to = LocalDate.of(Integer.parseInt(m.group(4)), Integer.parseInt(m.group(5)), Integer.parseInt(m.group(6)));
            return new TimeRange(from.atStartOfDay(), to.plusDays(1).atStartOfDay(), "显式日期区间");
        }
        if ((m = RECENT_DAYS.matcher(e)).find() || (m = PAST_DAYS.matcher(e)).find()) {
            int days = Integer.parseInt(m.group(1));
            // 「近N天」口径：含今天，从 D-(N-1) 00:00 到现在
            return new TimeRange(today.minusDays(days - 1L).atStartOfDay(), now.toLocalDateTime(),
                    "近" + days + "天（含今天，当日为累计值）");
        }
        if ((m = RECENT_HOURS.matcher(e)).find()) {
            int hours = Integer.parseInt(m.group(1));
            return new TimeRange(now.toLocalDateTime().minusHours(hours), now.toLocalDateTime(), "近" + hours + "小时");
        }
        return switch (e) {
            case "今天", "今日" -> new TimeRange(today.atStartOfDay(), now.toLocalDateTime(), "当日累计，非完整自然日");
            case "昨天", "昨日" -> new TimeRange(today.minusDays(1).atStartOfDay(), today.atStartOfDay(), "完整自然日");
            case "前天" -> new TimeRange(today.minusDays(2).atStartOfDay(), today.minusDays(1).atStartOfDay(), "完整自然日");
            case "本周", "这周" -> new TimeRange(
                    today.with(TemporalAdjusters.previousOrSame(java.time.DayOfWeek.MONDAY)).atStartOfDay(),
                    now.toLocalDateTime(), "本周（周一为起点，含今天）");
            case "上周" -> {
                LocalDate thisMonday = today.with(TemporalAdjusters.previousOrSame(java.time.DayOfWeek.MONDAY));
                yield new TimeRange(thisMonday.minusWeeks(1).atStartOfDay(), thisMonday.atStartOfDay(), "上周（周一至周日）");
            }
            case "本月", "这个月" -> new TimeRange(today.withDayOfMonth(1).atStartOfDay(), now.toLocalDateTime(), "本月（含今天）");
            case "上月" -> {
                LocalDate first = today.withDayOfMonth(1);
                yield new TimeRange(first.minusMonths(1).atStartOfDay(), first.atStartOfDay(), "上月（完整自然月）");
            }
            default -> throw ApiException.paramInvalid("无法解析时间表达式：" + expr);
        };
    }

    public static TimeRange resolve(String expr) {
        return resolve(expr, ZonedDateTime.now(DEFAULT_ZONE), DEFAULT_ZONE);
    }
}

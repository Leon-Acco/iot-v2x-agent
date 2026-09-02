package com.dst.v2xagent.capability;

import com.dst.v2xagent.common.ApiException;
import com.dst.v2xagent.common.TimeExpressionResolver;
import com.dst.v2xagent.common.TimeExpressionResolver.TimeRange;
import org.junit.jupiter.api.Test;

import java.time.LocalDateTime;
import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

/**
 * 时间归一化 + SQL 模板渲染 单元测试
 * 锚点固定，保证可复现
 */
class TimeAndSqlTemplateTest {

    private static final ZoneId ZONE = ZoneId.of("Asia/Shanghai");
    private static final ZonedDateTime ANCHOR = ZonedDateTime.of(2026, 8, 21, 16, 0, 0, 0, ZONE);

    @Test
    void 近7天_含今天() {
        TimeRange r = TimeExpressionResolver.resolve("近7天", ANCHOR, ZONE);
        assertEquals(LocalDateTime.of(2026, 8, 15, 0, 0), r.from());
        assertEquals(LocalDateTime.of(2026, 8, 21, 16, 0), r.to());
    }

    @Test
    void 昨天_完整自然日() {
        TimeRange r = TimeExpressionResolver.resolve("昨天", ANCHOR, ZONE);
        assertEquals(LocalDateTime.of(2026, 8, 20, 0, 0), r.from());
        assertEquals(LocalDateTime.of(2026, 8, 21, 0, 0), r.to());
    }

    @Test
    void 上周_周一起点() {
        TimeRange r = TimeExpressionResolver.resolve("上周", ANCHOR, ZONE);
        // 2026-08-21 是周五，本周一 08-17，上周一 08-10
        assertEquals(LocalDateTime.of(2026, 8, 10, 0, 0), r.from());
        assertEquals(LocalDateTime.of(2026, 8, 17, 0, 0), r.to());
    }

    @Test
    void 无法解析_抛参数异常() {
        assertThrows(ApiException.class, () -> TimeExpressionResolver.resolve("去年夏天", ANCHOR, ZONE));
    }

    @Test
    void 模板渲染_数组展开与空标记() {
        String template = "SELECT a FROM t WHERE (${vin_list_empty} OR vin IN (${vin_list})) AND fleet_id IN (${acl_fleet_ids}) LIMIT 100";
        SqlTemplateRenderer.RenderedSql r = SqlTemplateRenderer.render(template,
                Map.of("vin_list", List.of("V1", "V2"), "acl_fleet_ids", List.of("F1")), 100);
        assertTrue(r.sql().contains("(0 OR vin IN (?, ?))"));
        assertTrue(r.sql().contains("fleet_id IN (?)"));
        assertEquals(List.of("V1", "V2", "F1"), r.bindValues());
    }

    @Test
    void 模板渲染_空数组走旁路渲染NULL() {
        String template = "SELECT a FROM t WHERE (${vin_list_empty} OR vin IN (${vin_list})) LIMIT 100";
        // 参数缺失时：${vin_list_empty} 短路为 1，${vin_list} 渲染为 NULL 不绑定
        SqlTemplateRenderer.RenderedSql r = SqlTemplateRenderer.render(template, Map.of(), 100);
        assertTrue(r.sql().contains("(1 OR vin IN (NULL))"));
        assertTrue(r.bindValues().isEmpty());
    }

    @Test
    void 模板渲染_无旁路标记时空数组报错() {
        String template = "SELECT a FROM t WHERE vin IN (${vin_list}) LIMIT 10";
        assertThrows(ApiException.class, () -> SqlTemplateRenderer.render(template,
                Map.of("vin_list", List.of()), 100));
    }

    @Test
    void 模板渲染_缺失参数报错() {
        String template = "SELECT a FROM t WHERE fleet_id = ${fleet_id} LIMIT 10";
        assertThrows(ApiException.class, () -> SqlTemplateRenderer.render(template, Map.of(), 100));
    }

    @Test
    void 模板渲染_数组超限报错() {
        String template = "SELECT a FROM t WHERE vin IN (${vin_list}) LIMIT 10";
        List<String> vins = java.util.Collections.nCopies(51, "V");
        assertThrows(ApiException.class, () -> SqlTemplateRenderer.render(template, Map.of("vin_list", vins), 50));
    }
}

package com.dst.v2xagent.task;

import com.dst.v2xagent.capability.CapabilityRegistry;
import com.dst.v2xagent.capability.model.CapabilityDefinition;
import org.junit.jupiter.api.Test;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.*;

/**
 * 任务步骤精炼器单元测试：三级映射 / 时间归一 / 白名单 / 去重 / 跳过场景
 * （CapabilityRegistry 用桩子类替换，脱离 Spring 与 DB）
 */
class TaskStepRefinerTest {

    /** 桩注册中心：仅支持 get(id) 查内存定义（精炼器只用这一个方法） */
    static class StubRegistry extends CapabilityRegistry {
        private final Map<String, CapabilityDefinition> defs = new LinkedHashMap<>();

        StubRegistry() {
            super(null);
        }

        void add(CapabilityDefinition def) {
            defs.put(def.getId(), def);
        }

        @Override
        public Optional<CapabilityDefinition> get(String id) {
            return Optional.ofNullable(defs.get(id));
        }
    }

    /** 造一个标准查询能力：vehicle 必填 + time_range 相对时间 */
    private static CapabilityDefinition alarmDef() {
        CapabilityDefinition def = new CapabilityDefinition();
        def.setId("alarm_count_by_type");
        def.setDisplay("告警类型统计");
        def.setStatus("online");
        CapabilityDefinition.ParamDef vehicle = new CapabilityDefinition.ParamDef();
        vehicle.setName("vehicle");
        vehicle.setType("string");
        vehicle.setRequired(true);
        CapabilityDefinition.ParamDef time = new CapabilityDefinition.ParamDef();
        time.setName("time_range");
        time.setType("daterange");
        time.setRequired(true);
        time.setDefaultValue("近7天");
        def.setParams(List.of(vehicle, time));
        return def;
    }

    private TaskStepRefiner refiner(StubRegistry registry) {
        return new TaskStepRefiner(registry);
    }

    @Test
    void 中文名映射与时间display截取归一() {
        StubRegistry registry = new StubRegistry();
        registry.add(alarmDef());
        Map<String, Object> args = new LinkedHashMap<>();
        args.put("vehicle", "粤C10003");
        args.put("time_range_display", "近 7 天（2026-08-28 ~ 2026-09-04）");

        TaskStepRefiner.RefineResult r = refiner(registry).refine(List.of(
                new TaskStepRefiner.Frame("real_alarm_count#1", "告警类型统计", args)));

        assertEquals(1, r.steps().size());
        assertTrue(r.skipped().isEmpty());
        TaskStepRefiner.StepDraft step = r.steps().get(0);
        // 三级映射第一级：中文名 → registry id（帧 id real_alarm_count 与目录 id 不同源）
        assertEquals("alarm_count_by_type", step.capabilityId());
        assertEquals("告警类型统计", step.displayName());
        assertEquals("relative", step.timeMode());
        // display 截「（」前缀 + 去空格 → 相对词
        assertEquals("近7天", step.params().get("time_range"));
        assertEquals("粤C10003", step.params().get("vehicle"));
    }

    @Test
    void 相对词原文直用() {
        StubRegistry registry = new StubRegistry();
        registry.add(alarmDef());
        Map<String, Object> args = new LinkedHashMap<>();
        args.put("vehicle", "粤C10003");
        args.put("time_range", "近30天");

        TaskStepRefiner.RefineResult r = refiner(registry).refine(List.of(
                new TaskStepRefiner.Frame("count_alarms_by_type", "告警类型统计", args)));

        assertEquals("近30天", r.steps().get(0).params().get("time_range"));
        assertEquals("relative", r.steps().get(0).timeMode());
    }

    @Test
    void 无映射工具跳过并说明原因() {
        StubRegistry registry = new StubRegistry();
        registry.add(alarmDef());
        Map<String, Object> args = Map.of("vehicle", "粤C10003");

        TaskStepRefiner.RefineResult r = refiner(registry).refine(List.of(
                new TaskStepRefiner.Frame("query_vehicle_info#1", "车辆档案", args)));

        assertTrue(r.steps().isEmpty());
        assertEquals(1, r.skipped().size());
        assertTrue(r.skipped().get(0).reason().contains("不支持任务化"));
    }

    @Test
    void 白名单剔除杂键与权限键() {
        StubRegistry registry = new StubRegistry();
        registry.add(alarmDef());
        Map<String, Object> args = new LinkedHashMap<>();
        args.put("vehicle", "粤C10003");
        args.put("time_range", "近7天");
        args.put("hours", 24);                       // copilot 透传杂键（能力未声明）
        args.put("_editable", List.of());            // 前端回显辅助键
        args.put("acl_org_ids", List.of("某车队"));   // 权限键（执行期由管线注入）
        args.put("time_from", "2026-08-28 00:00:00"); // 派生绝对区间
        args.put("time_to", "2026-09-04 23:59:59");

        TaskStepRefiner.RefineResult r = refiner(registry).refine(List.of(
                new TaskStepRefiner.Frame("count_alarms_by_type", "告警类型统计", args)));

        Map<String, Object> params = r.steps().get(0).params();
        assertEquals(2, params.size());
        assertTrue(params.containsKey("vehicle") && params.containsKey("time_range"));
    }

    @Test
    void 相同能力相同参数去重只留一个() {
        StubRegistry registry = new StubRegistry();
        registry.add(alarmDef());
        Map<String, Object> args = Map.of("vehicle", "粤C10003", "time_range", "近7天");

        TaskStepRefiner.RefineResult r = refiner(registry).refine(List.of(
                new TaskStepRefiner.Frame("count_alarms_by_type#1", "告警类型统计", args),
                new TaskStepRefiner.Frame("count_alarms_by_type#2", "告警类型统计", args)));

        assertEquals(1, r.steps().size());
        assertEquals(1, r.steps().get(0).seq());
    }

    @Test
    void 多帧混合可固化与跳过并存() {
        StubRegistry registry = new StubRegistry();
        registry.add(alarmDef());
        Map<String, Object> okArgs = Map.of("vehicle", "粤C10003", "time_range", "近7天");
        Map<String, Object> badArgs = Map.of("vehicle", "粤C10003");

        TaskStepRefiner.RefineResult r = refiner(registry).refine(List.of(
                new TaskStepRefiner.Frame("count_alarms_by_type#1", "告警类型统计", okArgs),
                new TaskStepRefiner.Frame("query_vehicle_location#2", "最后位置", badArgs)));

        assertEquals(1, r.steps().size());
        assertEquals(1, r.skipped().size());
    }

    @Test
    void vin_list直存可满足vehicle必填() {
        StubRegistry registry = new StubRegistry();
        registry.add(alarmDef());
        Map<String, Object> args = new LinkedHashMap<>();
        args.put("time_range", "近7天");
        args.put("vin_list", List.of("LSV12345678901234"));

        TaskStepRefiner.RefineResult r = refiner(registry).refine(List.of(
                new TaskStepRefiner.Frame("count_alarms_by_type#1", "告警类型统计", args)));

        assertEquals(1, r.steps().size());
        assertEquals(List.of("LSV12345678901234"), r.steps().get(0).params().get("vin_list"));
    }

    @Test
    void 必填缺失时跳过() {
        StubRegistry registry = new StubRegistry();
        CapabilityDefinition strict = new CapabilityDefinition();
        strict.setId("strict_query");
        strict.setDisplay("严格查询");
        strict.setStatus("online");
        CapabilityDefinition.ParamDef vehicle = new CapabilityDefinition.ParamDef();
        vehicle.setName("vehicle");
        vehicle.setType("string");
        vehicle.setRequired(true);
        strict.setParams(List.of(vehicle));
        registry.add(strict);

        TaskStepRefiner.RefineResult r = refiner(registry).refine(List.of(
                new TaskStepRefiner.Frame("strict_query#1", "严格查询", Map.of())));

        assertTrue(r.steps().isEmpty());
        assertTrue(r.skipped().get(0).reason().contains("缺少必填参数"));
    }

    @Test
    void display无相对词时兜底绝对区间() {
        StubRegistry registry = new StubRegistry();
        registry.add(alarmDef());
        Map<String, Object> args = new LinkedHashMap<>();
        args.put("vehicle", "粤C10003");
        args.put("time_range_display", "自定义（2026-08-01 ~ 2026-08-31）");

        TaskStepRefiner.RefineResult r = refiner(registry).refine(List.of(
                new TaskStepRefiner.Frame("count_alarms_by_type#1", "告警类型统计", args)));

        assertEquals(1, r.steps().size());
        assertEquals("absolute", r.steps().get(0).timeMode());
        assertEquals("2026-08-01 到 2026-08-31", r.steps().get(0).params().get("time_range"));
    }

    @Test
    void 时间词不可解析时跳过() {
        StubRegistry registry = new StubRegistry();
        registry.add(alarmDef());
        Map<String, Object> args = new LinkedHashMap<>();
        args.put("vehicle", "粤C10003");
        args.put("time_range_display", "很久很久以前（2026-08-01 ~ 2026-08-31）");

        TaskStepRefiner.RefineResult r = refiner(registry).refine(List.of(
                new TaskStepRefiner.Frame("count_alarms_by_type#1", "告警类型统计", args)));

        // 相对词「很久很久以前」不可解析，兜底绝对区间可解析 → 仍可固化（absolute）
        assertEquals(1, r.steps().size());
        assertEquals("absolute", r.steps().get(0).timeMode());
    }

    @Test
    void 相对词提取与绝对区间提取的纯函数行为() {
        assertEquals("近7天", TaskStepRefiner.extractRelativeWord("近 7 天（2026-08-28 ~ 2026-09-04）"));
        assertEquals("昨天", TaskStepRefiner.extractRelativeWord("昨天（09-03 00:00 ~ 09-04 00:00）"));
        assertNull(TaskStepRefiner.extractRelativeWord("（2026-08-01 ~ 2026-08-31）"));
        assertNull(TaskStepRefiner.extractRelativeWord(null));
        assertEquals("2026-08-01 到 2026-08-31",
                TaskStepRefiner.extractExplicitRange("自定义（2026-08-01 ~ 2026-08-31）"));
        assertNull(TaskStepRefiner.extractExplicitRange("无日期区间"));
    }
}

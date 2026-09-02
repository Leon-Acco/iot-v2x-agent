package com.dst.v2xagent.capability;

import com.dst.v2xagent.capability.model.CapabilityDefinition;
import com.dst.v2xagent.common.ApiException;
import com.dst.v2xagent.common.PermissionContext;
import com.dst.v2xagent.common.TimeExpressionResolver;
import com.dst.v2xagent.common.TimeExpressionResolver.TimeRange;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Duration;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;

/**
 * 参数解析管线（八步，顺序不可变）
 * 1 未知参数剔除（严格模式）→ 2 类型/必填校验 → 3 时间归一 → 4 实体解析
 * → 5 枚举校验 → 6 上限约束 → 7 权限注入（安全命门）→ 8 参数指纹
 *
 * 第 7 步：ACL 参数与业务参数在类型上就是两个集合，模型无法传入或覆盖。
 */
@Component
@RequiredArgsConstructor
public class ParamResolver {

    private final VehicleResolver vehicleResolver;
    private final FleetMappingService fleetMappingService;

    /** 解析产物：绑定参数 + 回显信息 + 缓存指纹 */
    public record ResolvedParams(
            Map<String, Object> bindParams,
            TimeRange timeRange,
            List<String> resolvedVins,
            String fingerprint
    ) {}

    /**
     * 八步解析。模型原始参数只含业务参数，权限参数由本管线注入。
     * @param def       capability 定义
     * @param rawParams 模型产出的原始参数
     * @param ctx       权限上下文
     * @param anchor    时间锚点（请求时刻）
     */
    public ResolvedParams resolve(CapabilityDefinition def, Map<String, Object> rawParams,
                                  PermissionContext ctx, java.time.ZonedDateTime anchor) {
        Map<String, Object> raw = rawParams == null ? Map.of() : rawParams;
        Map<String, Object> params = new HashMap<>();

        // ── 第 1 步：未知参数剔除（严格模式），防止「偷渡参数」式注入 ──
        Map<String, CapabilityDefinition.ParamDef> declared = new HashMap<>();
        for (CapabilityDefinition.ParamDef p : def.getParams()) declared.put(p.getName(), p);
        // ACL 保留字：模型传入即拒绝（权限命门，模型无法覆盖）
        for (String key : raw.keySet()) {
            if (key.startsWith("acl_")) {
                throw ApiException.paramInvalid("参数 " + key + " 为权限保留字段，不允许传入");
            }
            if (!declared.containsKey(key)) {
                throw ApiException.schemaInvalid("capability " + def.getId() + " 不接受参数: " + key);
            }
        }

        // ── 第 2 步：类型与必填校验 ──
        for (CapabilityDefinition.ParamDef p : def.getParams()) {
            Object value = raw.get(p.getName());
            if (value == null) value = p.getDefaultValue();
            if (value == null) {
                if (p.isRequired()) {
                    // vehicle 必填可被 vin_list 满足（澄清选择/多轮继承只带 vin_list）
                    if ("vehicle".equals(p.getName()) && raw.get("vin_list") != null) {
                        continue;
                    }
                    // 缺必填 → 上层转澄清
                    throw new MissingParamException(p.getName(), def.getId());
                }
                continue;
            }
            params.put(p.getName(), coerceType(p, value));
        }

        // ── 第 3 步：时间表达式解析 → 绝对区间 ──
        TimeRange timeRange = null;
        for (CapabilityDefinition.ParamDef p : def.getParams()) {
            if (!"daterange".equals(p.getType()) || !params.containsKey(p.getName())) continue;
            timeRange = TimeExpressionResolver.resolve(String.valueOf(params.get(p.getName())), anchor,
                    java.time.ZoneId.of("Asia/Shanghai"));
            params.put("time_from", timeRange.from());
            params.put("time_to", timeRange.to());
        }

        // ── 第 4 步：实体解析：车牌/VIN → vin_list（先过数据权限）──
        List<String> resolvedVins = new ArrayList<>();
        if (params.containsKey("vehicle") && !params.containsKey("vin_list")) {
            VehicleResolver.ResolveResult rr = vehicleResolver.resolve(
                    String.valueOf(params.get("vehicle")), fleetMappingService.expandOrgNames(ctx));
            if (rr.notFound()) {
                throw ApiException.paramInvalid("未找到车辆：" + params.get("vehicle"));
            }
            if (rr.ambiguous()) {
                throw new AmbiguousVehicleException(def.getId(), rr.candidates());
            }
            params.put("vin_list", rr.vins());
            params.remove("vehicle");
        }
        if (params.get("vin_list") instanceof Collection<?> vins) {
            vins.forEach(v -> resolvedVins.add(String.valueOf(v)));
        }

        // ── 第 5 步：枚举与字典校验 ──
        for (CapabilityDefinition.ParamDef p : def.getParams()) {
            if (p.getEnumValues() == null || p.getEnumValues().isEmpty() || !params.containsKey(p.getName())) continue;
            String v = String.valueOf(params.get(p.getName()));
            if (!p.getEnumValues().contains(v)) {
                throw ApiException.paramInvalid("参数 " + p.getName() + " 取值非法: " + v + "，合法值: " + p.getEnumValues());
            }
        }

        // ── 第 6 步：上限约束 ──
        if (timeRange != null) {
            long spanDays = Duration.between(timeRange.from(), timeRange.to()).toDays();
            int maxSpan = def.getParams().stream().filter(p -> "daterange".equals(p.getType()))
                    .map(CapabilityDefinition.ParamDef::getMaxSpanDays)
                    .filter(java.util.Objects::nonNull).findFirst()
                    .orElse(def.getLimits().getMaxSpanDays());
            if (spanDays > maxSpan) {
                throw ApiException.paramInvalid("时间跨度 " + spanDays + " 天超过上限 " + maxSpan + " 天，请缩小范围");
            }
        }
        for (CapabilityDefinition.ParamDef p : def.getParams()) {
            if (p.getMaxItems() != null && params.get(p.getName()) instanceof Collection<?> c && c.size() > p.getMaxItems()) {
                throw ApiException.paramInvalid("参数 " + p.getName() + " 数量超过上限 " + p.getMaxItems());
            }
        }

        // ── 第 7 步：权限注入（模型无法传入或覆盖这些参数）──
        if (ctx.fleetIds() == null || ctx.fleetIds().isEmpty()) {
            throw ApiException.scopeDenied("当前账号没有任何车队数据权限");
        }
        params.put("acl_fleet_ids", List.copyOf(ctx.fleetIds()));
        // by_org 策略：车队标识映射为 Doris org_name（未命中原样透传）
        params.put("acl_org_ids", List.copyOf(fleetMappingService.expandOrgNames(ctx)));

        // ── 第 8 步：参数指纹（排序归一化 → sha256），用于缓存 key 与幂等 ──
        String fingerprint = sha256(canonical(params));

        return new ResolvedParams(params, timeRange, resolvedVins, fingerprint);
    }

    /** 缺必填参数异常：上层转澄清气泡 */
    public static class MissingParamException extends RuntimeException {
        public final String paramName;
        public final String capabilityId;
        public MissingParamException(String paramName, String capabilityId) {
            super("缺少必填参数: " + paramName);
            this.paramName = paramName;
            this.capabilityId = capabilityId;
        }
    }

    /** 车辆多命中异常：上层转澄清气泡 */
    public static class AmbiguousVehicleException extends RuntimeException {
        public final String capabilityId;
        public final List<VehicleResolver.VehicleCandidate> candidates;
        public AmbiguousVehicleException(String capabilityId, List<VehicleResolver.VehicleCandidate> candidates) {
            super("车辆指代歧义，命中 " + candidates.size() + " 台");
            this.capabilityId = capabilityId;
            this.candidates = candidates;
        }
    }

    /** 类型强转：模型产出的 JSON 值 → 声明类型 */
    private Object coerceType(CapabilityDefinition.ParamDef p, Object value) {
        return switch (p.getType()) {
            case "int" -> value instanceof Number n ? n.intValue() : Integer.parseInt(String.valueOf(value));
            case "array<string>" -> {
                if (value instanceof Collection<?> c) {
                    yield c.stream().map(String::valueOf).toList();
                }
                yield List.of(String.valueOf(value));
            }
            case "daterange" -> {
                // 模型输出形如 {"expr": "近7天"}，只取表达式文本
                if (value instanceof Map<?, ?> m && m.get("expr") != null) {
                    yield String.valueOf(m.get("expr"));
                }
                yield String.valueOf(value);
            }
            default -> String.valueOf(value);
        };
    }

    /** 参数规范化序列化（key 排序，集合排序），保证同义参数同指纹 */
    private String canonical(Map<String, Object> params) {
        TreeMap<String, Object> sorted = new TreeMap<>(params);
        StringBuilder sb = new StringBuilder();
        sorted.forEach((k, v) -> {
            sb.append(k).append('=');
            if (v instanceof Collection<?> c) {
                sb.append(c.stream().map(String::valueOf).sorted().toList());
            } else {
                sb.append(v);
            }
            sb.append(';');
        });
        return sb.toString();
    }

    private String sha256(String text) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            byte[] hash = digest.digest(text.getBytes(StandardCharsets.UTF_8));
            StringBuilder hex = new StringBuilder();
            for (byte b : hash) hex.append(String.format("%02x", b));
            return hex.toString();
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }
}

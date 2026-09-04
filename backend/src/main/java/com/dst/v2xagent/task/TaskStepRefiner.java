package com.dst.v2xagent.task;

import com.dst.v2xagent.capability.CapabilityRegistry;
import com.dst.v2xagent.capability.model.CapabilityDefinition;
import com.dst.v2xagent.common.TimeExpressionResolver;
import com.dst.v2xagent.copilot.CopilotToolCatalog;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

import java.time.ZonedDateTime;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * 会话工具帧 → 可执行任务步骤的精炼归一器（纯逻辑，无 DB 写入，便于单测）。
 * 核心问题：对话流工具与 capability registry 是两套 id 体系（帧 id 形如 real_alarm_count#1，
 * 目录 id 形如 count_alarms_by_type），只靠中文展示名稳定交联——故采用三级映射链。
 * 归一产物 steps 只存白名单业务参数（相对时间词/车牌原文），执行时由八步解析现算，任务永不过期。
 */
@Component
@RequiredArgsConstructor
public class TaskStepRefiner {

    private final CapabilityRegistry registry;

    /** 输入：会话中的一帧工具调用（id=帧 capabilityId 可能带 #seq，name=中文展示名，args=参数帧） */
    public record Frame(String id, String name, Map<String, Object> args) {}

    /** 输出：可固化步骤草稿（时间模式 relative=相对词现算 / absolute=固定区间） */
    public record StepDraft(int seq, String capabilityId, String toolFrameId,
                            String displayName, String timeMode, Map<String, Object> params) {}

    /** 被跳过的帧（前端弹窗提示用户哪些工具不支持固化及原因） */
    public record Skipped(String tool, String displayName, String reason) {}

    /** 精炼结果：步骤 + 跳过清单；全帧不可映射时 steps 为空（上层拒绝创建） */
    public record RefineResult(List<StepDraft> steps, List<Skipped> skipped) {}

    /** 单任务最大步骤数（与执行预算上界绑定：6 步 × 15s < 120s 默认预算） */
    public static final int MAX_STEPS = 6;

    /** 相对时间词预检用的固定时区（与 ParamResolver/TimeExpressionResolver 保持一致） */
    private static final ZoneId ZONE = ZoneId.of("Asia/Shanghai");

    /**
     * 逐帧精炼。顺序固定：三级映射 → 去重 → 状态校验 → 时间归一 → 车辆参数 → 白名单 → 必填预检。
     */
    public RefineResult refine(List<Frame> frames) {
        List<StepDraft> steps = new ArrayList<>();
        List<Skipped> skipped = new ArrayList<>();
        if (frames == null || frames.isEmpty()) {
            return new RefineResult(steps, skipped);
        }
        for (Frame f : frames) {
            // ── 1. 能力映射三级链：中文名 → 帧 id # 前段查目录 → registry 直查 ──
            String capabilityId = mapCapability(f);
            if (capabilityId == null) {
                skipped.add(new Skipped(frameKey(f), f.name(), "该工具不支持任务化（无对应数据能力）"));
                continue;
            }
            CapabilityDefinition def = registry.get(capabilityId).orElse(null);
            if (def == null) {
                skipped.add(new Skipped(frameKey(f), f.name(), "能力定义不存在: " + capabilityId));
                continue;
            }
            // ── 2. 状态校验：仅 online 能力可固化 ──
            if (!"online".equals(def.getStatus())) {
                skipped.add(new Skipped(frameKey(f), f.name(), "能力已下线: " + def.getDisplay()));
                continue;
            }
            Map<String, Object> args = f.args() == null ? Map.of() : f.args();
            // ── 3. 参数白名单归一（剔除 acl_*/_ 前缀与能力未声明的杂键，思路同 TaskCardService.sanitizeParams） ──
            Map<String, Object> params = whitelistParams(def, args);
            // ── 4. 时间参数归一：相对词直用 / time_range_display 截取前缀 / 绝对区间兜底 ──
            String timeMode = normalizeTimeRange(def, params, f);
            if (timeMode == null) {
                skipped.add(new Skipped(frameKey(f), f.name(), "时间范围无法固化"));
                continue;
            }
            // ── 5. 必填预检：归一后仍缺且无默认值的必填参数不固化（执行期才报错体验太差） ──
            String missingRequired = missingRequired(def, params);
            if (missingRequired != null) {
                skipped.add(new Skipped(frameKey(f), f.name(), "缺少必填参数 " + missingRequired));
                continue;
            }
            // ── 6. 去重：同能力同参数的重复帧（模型重试）只留最后一个 ──
            dedup(steps, capabilityId, params);
            if (steps.size() + 1 > MAX_STEPS) {
                skipped.add(new Skipped(frameKey(f), f.name(), "步骤数超过上限 " + MAX_STEPS));
                continue;
            }
            steps.add(new StepDraft(steps.size() + 1, capabilityId, f.id(),
                    def.getDisplay(), timeMode, params));
        }
        // 重排序号（去重移除中间帧后 seq 保持连续）
        List<StepDraft> renumbered = new ArrayList<>(steps.size());
        for (int i = 0; i < steps.size(); i++) {
            StepDraft d = steps.get(i);
            renumbered.add(new StepDraft(i + 1, d.capabilityId(), d.toolFrameId(),
                    d.displayName(), d.timeMode(), d.params()));
        }
        return new RefineResult(renumbered, skipped);
    }

    /**
     * 三级映射：中文名（两套体系的唯一稳定交联点）→ 帧 id # 前段查目录 → registry 直查。
     */
    private String mapCapability(Frame f) {
        String byName = CopilotToolCatalog.registryIdOf(f.name());
        if (byName != null) {
            return byName;
        }
        String frameIdPrefix = idPrefix(f.id());
        if (frameIdPrefix != null) {
            String byId = CopilotToolCatalog.registryIdOf(frameIdPrefix);
            if (byId != null) {
                return byId;
            }
            if (registry.get(frameIdPrefix).isPresent()) {
                return frameIdPrefix;
            }
        }
        return null;
    }

    /** 帧 id 的 # 前段（real_alarm_count#1 → real_alarm_count）；null 安全 */
    private static String idPrefix(String id) {
        if (id == null || id.isBlank()) {
            return null;
        }
        return id.split("#")[0].trim();
    }

    /** 帧标识（skipped 提示用）：优先展示名，缺失回退 id */
    private static String frameKey(Frame f) {
        return f.name() != null && !f.name().isBlank() ? f.name() : String.valueOf(f.id());
    }

    /** 回显辅助键 / 管线派生键：不透传进任务模板（time_range 本身是合法声明参数，单独归一） */
    private static final Set<String> FRAME_ARTIFACT_KEYS = Set.of("time_from", "time_to", "time_range_display");

    /**
     * 白名单参数归一：只保留目标能力声明的参数名；
     * acl_ 前缀键直接丢弃（权限参数只能由执行管线注入），_ 前缀键与回显派生键一并剔除。
     */
    private static Map<String, Object> whitelistParams(CapabilityDefinition def, Map<String, Object> args) {
        Set<String> declared = new LinkedHashSet<>();
        for (CapabilityDefinition.ParamDef p : def.getParams()) {
            if (p.getName() != null) {
                declared.add(p.getName());
            }
        }
        Map<String, Object> out = new LinkedHashMap<>();
        for (Map.Entry<String, Object> e : args.entrySet()) {
            String key = e.getKey();
            if (key == null || key.startsWith("acl_") || key.startsWith("_") || FRAME_ARTIFACT_KEYS.contains(key)) {
                continue;
            }
            if (declared.contains(key) && e.getValue() != null && !String.valueOf(e.getValue()).isBlank()) {
                out.put(key, e.getValue());
            }
        }
        // vin_list 是执行管线认识的特权参数（可满足 vehicle 必填），在声明名单外也要保留
        Object vins = args.get("vin_list");
        if (vins instanceof List<?> list && !list.isEmpty()) {
            out.putIfAbsent("vin_list", list);
        }
        return out;
    }

    /**
     * 时间参数归一（原地修改 params）。返回 timeMode（relative/absolute），null 表示无法固化。
     * 优先级：time_range 相对词原文 → time_range_display 截「（」前缀 → 绝对区间提取兜底。
     */
    private String normalizeTimeRange(CapabilityDefinition def, Map<String, Object> params, Frame f) {
        String daterangeParam = daterangeParamName(def);
        if (daterangeParam == null) {
            return "relative"; // 无时间参数的能力（如车辆档案），无时间可固化
        }
        Map<String, Object> args = f.args() == null ? Map.of() : f.args();
        // 路径 1：specialist 路径 args 直接带相对词原文
        Object raw = args.get("time_range");
        if (raw != null && precheck(String.valueOf(raw))) {
            params.put(daterangeParam, String.valueOf(raw));
            return "relative";
        }
        // 路径 2：time_range_display 固定格式「近 7 天（2026-08-28 ~ 2026-09-04）」——截取「（」前缀
        Object display = args.get("time_range_display");
        if (display != null) {
            String word = extractRelativeWord(String.valueOf(display));
            if (word != null && precheck(word)) {
                params.put(daterangeParam, word);
                return "relative";
            }
            // 路径 3 兜底：提取 yyyy-MM-dd ~ yyyy-MM-dd 区间 → TimeExpressionResolver 的 EXPLICIT_RANGE 词形
            String range = extractExplicitRange(String.valueOf(display));
            if (range != null && precheck(range)) {
                params.put(daterangeParam, range);
                return "absolute";
            }
        }
        // 无任何时间线索：留空走能力默认值（有默认值才可固化）
        return missingTimeOk(def) ? "relative" : null;
    }

    /** 能力的 daterange 参数名（约定单 daterange）；无则 null */
    private static String daterangeParamName(CapabilityDefinition def) {
        for (CapabilityDefinition.ParamDef p : def.getParams()) {
            if ("daterange".equals(p.getType()) && p.getName() != null) {
                return p.getName();
            }
        }
        return null;
    }

    /** 无时间线索时：daterange 参数有默认值或非必填才允许固化（执行期走默认） */
    private static boolean missingTimeOk(CapabilityDefinition def) {
        for (CapabilityDefinition.ParamDef p : def.getParams()) {
            if ("daterange".equals(p.getType())) {
                return p.getDefaultValue() != null || !p.isRequired();
            }
        }
        return true;
    }

    /** 「近 7 天（2026-08-28 ~ 2026-09-04）」→「近7天」（去内部空格）；无「（」前缀返回 null */
    static String extractRelativeWord(String display) {
        if (display == null) {
            return null;
        }
        int idx = display.indexOf('（');
        if (idx <= 0) {
            idx = display.indexOf('(');
        }
        if (idx <= 0) {
            return null;
        }
        String word = display.substring(0, idx).replaceAll("\\s+", "");
        return word.isBlank() ? null : word;
    }

    /** 提取「yyyy-MM-dd ~ yyyy-MM-dd」并转成解析器认识的「yyyy-MM-dd 到 yyyy-MM-dd」；无则 null */
    static String extractExplicitRange(String display) {
        if (display == null) {
            return null;
        }
        java.util.regex.Matcher m = java.util.regex.Pattern.compile(
                "(\\d{4}-\\d{2}-\\d{2})\\s*~\\s*(\\d{4}-\\d{2}-\\d{2})").matcher(display);
        if (m.find()) {
            return m.group(1) + " 到 " + m.group(2);
        }
        return null;
    }

    /** 相对词可解析性预检（不可解析的时间词固化后执行必炸，创建期就拦下） */
    private static boolean precheck(String expr) {
        try {
            TimeExpressionResolver.resolve(expr, ZonedDateTime.now(), ZONE);
            return true;
        } catch (Exception e) {
            return false;
        }
    }

    /** 归一后仍缺的必填参数名（vehicle 必填可被 vin_list 满足）；全部满足返回 null */
    private static String missingRequired(CapabilityDefinition def, Map<String, Object> params) {
        for (CapabilityDefinition.ParamDef p : def.getParams()) {
            if (!p.isRequired() || p.getName() == null) {
                continue;
            }
            if (params.containsKey(p.getName()) || p.getDefaultValue() != null) {
                continue;
            }
            if ("vehicle".equals(p.getName()) && params.containsKey("vin_list")) {
                continue;
            }
            return p.getName();
        }
        return null;
    }

    /** 去重：移除与 (capabilityId, params) 完全相同的已有步骤（模型重试同查询只留最新） */
    private static void dedup(List<StepDraft> steps, String capabilityId, Map<String, Object> params) {
        String fingerprint = capabilityId + "|" + params;
        steps.removeIf(s -> (s.capabilityId() + "|" + s.params()).equals(fingerprint));
    }
}

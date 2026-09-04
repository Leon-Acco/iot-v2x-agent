package com.dst.v2xagent.copilot;

import com.dst.v2xagent.capability.CapabilityRegistry;
import com.dst.v2xagent.capability.model.CapabilityDefinition;
import com.dst.v2xagent.common.PermissionContext;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.agentscope.core.tool.Tool;
import io.agentscope.core.tool.ToolParam;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Copilot 工具集（AgentScope @Tool 注解驱动，每次运行构造一个实例）。
 * 每个工具：解析参数 -> 调 SimQueries（确定性 SQL + ACL）-> 桥推表格/图表给前端
 * -> 返回紧凑摘要 JSON 给模型。模型看不到 SQL，也碰不到权限。
 * 另含 load_capability_guide（说明书工具）：capability 目录 -> 富说明书按需加载（loadSkill 模式）。
 */
public class CopilotTools {

    private final CopilotQueryRouter queries;
    private final PermissionContext ctx;
    private final ToolResultBridge bridge;
    /** capability 注册中心（说明书数据源）；可空——缺失时 guide 降级为目录级说明 */
    private final CapabilityRegistry registry;
    /** 任务服务 + 调度器（save_task 工具依赖）；可空——缺失时保存任务不可用 */
    private final com.dst.v2xagent.task.TaskService taskService;
    private final com.dst.v2xagent.task.TaskSchedulerHolder taskScheduler;
    /** 当前会话 id（跨轮「把刚才的存为任务」回退读上一轮帧用）；可空 */
    private final String sessionId;
    private final ObjectMapper mapper = new ObjectMapper();

    /** 本轮已读说明书次数（软预算：每轮最多 3 个） */
    private int guideReads = 0;

    public CopilotTools(CopilotQueryRouter queries, PermissionContext ctx, ToolResultBridge bridge) {
        this(queries, ctx, bridge, null, null, null, null);
    }

    public CopilotTools(CopilotQueryRouter queries, PermissionContext ctx, ToolResultBridge bridge,
                        CapabilityRegistry registry) {
        this(queries, ctx, bridge, registry, null, null, null);
    }

    public CopilotTools(CopilotQueryRouter queries, PermissionContext ctx, ToolResultBridge bridge,
                        CapabilityRegistry registry,
                        com.dst.v2xagent.task.TaskService taskService,
                        com.dst.v2xagent.task.TaskSchedulerHolder taskScheduler,
                        String sessionId) {
        this.queries = queries;
        this.ctx = ctx;
        this.bridge = bridge;
        this.registry = registry;
        this.taskService = taskService;
        this.taskScheduler = taskScheduler;
        this.sessionId = sessionId;
    }

    // ---------- 任务固化（save_task：模型在正常 plan 流程中自主调用） ----------

    /**
     * 把本轮（或会话最近一轮）已执行的查询固化为可重复执行的任务。
     * 帧来源优先级：本轮 bridge 帧（模型先查后存）→ 会话上一轮 payload 帧（跨轮「把刚才的存为任务」）。
     */
    @Tool(name = "save_task", description = "把当前会话已执行的查询固化为可重复执行的任务，供用户一键重跑或定时自动执行。"
            + "用户说「存为任务 / 保存这个查询 / 每天早上自动查一遍 / 定时执行」时调用。"
            + "调用时机：先完成用户要的查询（或本轮已有查询结果），再调用本工具固化。"
            + "任务执行时会自动重放全部查询步骤并用分析模型生成分析报告。")
    public String saveTask(
            @ToolParam(name = "title", required = false,
                    description = "任务标题（简短动词短语，如「粤C10003 周度告警巡检」）；留空自动取用户提问") String title,
            @ToolParam(name = "cron_expr", required = false,
                    description = "6 位 cron 表达式（Asia/Shanghai）。常用：每天08:00=0 0 8 * * *；"
                            + "每周一08:00=0 0 8 ? * MON；每小时整点=0 0 * * * *。留空=仅手动执行") String cronExpr,
            @ToolParam(name = "time_range", required = false,
                    description = "覆盖各步骤的时间范围相对词（如 近7天 / 近30天 / 今天 / 昨天）；"
                            + "用户要求定时任务固定查某个窗口时使用（如每天查「昨天」）；留空保持原查询时间") String timeRange) {
        if (taskService == null) {
            return jsonMsg("unavailable", "任务服务不可用，请告知用户稍后再试。");
        }
        try {
            // 1) 帧收集：本轮优先，跨轮回退会话最近一轮 payload
            List<TaskFrame> frames = collectFrames();
            if (frames.isEmpty()) {
                return jsonMsg("no_frames", "本轮与最近一轮都没有可固化的查询步骤。"
                        + "请先调用查询工具完成用户要的查询，再保存任务。");
            }
            // 2) time_range 覆盖预检（非法时间词直接报给模型，不落库）
            if (timeRange != null && !timeRange.isBlank()) {
                try {
                    com.dst.v2xagent.common.TimeExpressionResolver.resolve(timeRange.trim(),
                            java.time.ZonedDateTime.now(), java.time.ZoneId.of("Asia/Shanghai"));
                } catch (Exception e) {
                    return jsonMsg("bad_time_range", "time_range 无法解析：" + timeRange
                            + "。请使用 近N天 / 今天 / 昨天 / 本周 / yyyy-MM-dd 到 yyyy-MM-dd 等表达。");
                }
            }
            // 3) 创建（sourceQuestion 用 title 兜底——工具层拿不到用户原文）
            String taskTitle = title == null || title.isBlank() ? "对话固化的任务" : title.trim();
            List<com.dst.v2xagent.task.TaskStepRefiner.Frame> refineFrames = frames.stream()
                    .map(f -> new com.dst.v2xagent.task.TaskStepRefiner.Frame(f.id(), f.name(), f.args()))
                    .toList();
            Map<String, Object> created = taskService.createFromSession(ctx,
                    new com.dst.v2xagent.task.TaskService.CreateRequest(
                            taskTitle, refineFrames, sessionId, taskTitle, Boolean.TRUE));
            // 4) 时间覆盖（创建后直接改 steps 里的时间词——走 service 提供的覆盖入口）
            String applyNote = "";
            if (timeRange != null && !timeRange.isBlank()) {
                taskService.overrideTimeRange(((Number) created.get("id")).longValue(), timeRange.trim());
                applyNote = "；时间范围已统一覆盖为「" + timeRange.trim() + "」";
            }
            // 5) 定时注册
            String scheduleNote = "手动任务（可在任务中心一键执行）";
            if (cronExpr != null && !cronExpr.isBlank()) {
                long taskId = ((Number) created.get("id")).longValue();
                Map<String, Object> sch = taskService.setSchedule(ctx, taskId,
                        new com.dst.v2xagent.task.TaskService.ScheduleRequest(true, cronExpr.trim()));
                if (taskScheduler != null) {
                    taskScheduler.schedule(taskId, cronExpr.trim());
                }
                scheduleNote = "定时任务（cron=" + cronExpr.trim()
                        + "，下次触发 " + sch.get("nextFireAt") + "）";
            }
            // 6) 返回给模型的摘要（steps 是 StepDraft record，须经 Jackson 转换再取字段）
            Map<String, Object> out = new LinkedHashMap<>();
            out.put("status", "ok");
            out.put("taskId", created.get("id"));
            out.put("title", created.get("title"));
            List<Map<String, Object>> stepMaps = mapper.convertValue(created.get("steps"),
                    new com.fasterxml.jackson.core.type.TypeReference<List<Map<String, Object>>>() {});
            out.put("steps", stepMaps.stream().map(s -> String.valueOf(s.get("displayName"))).toList());
            out.put("skipped", created.get("skipped"));
            out.put("schedule", scheduleNote);
            out.put("note", "任务已保存" + applyNote + "，每次执行会重放全部查询并生成分析报告；"
                    + "请在回复里告知用户任务已创建、如何触发、下次执行时间。");
            return toJson(out);
        } catch (Exception e) {
            return jsonMsg("error", "任务保存失败：" + rootMsg(e));
        }
    }

    /** 帧三元组（bridge 帧与会话 payload 帧统一形状） */
    private record TaskFrame(String id, String name, Map<String, Object> args) {}

    /** 收集可固化帧：本轮 bridge 帧；为空回退会话最近一轮 assistant payload 的 tools */
    private List<TaskFrame> collectFrames() {
        List<TaskFrame> out = new ArrayList<>();
        for (ToolResultBridge.FrameRecord f : bridge.frames()) {
            out.add(new TaskFrame(f.id(), f.name(), f.args()));
        }
        if (!out.isEmpty() || sessionId == null) {
            return out;
        }
        // 跨轮回退：TaskService 读会话最近一轮 payload 帧（「把刚才的存为任务」）
        try {
            for (com.dst.v2xagent.task.TaskStepRefiner.Frame f : taskService.lastSessionFrames(ctx.tenantId(), sessionId)) {
                out.add(new TaskFrame(f.id(), f.name(), f.args()));
            }
        } catch (Exception ignore) {
            // 回退失败按无帧处理
        }
        return out;
    }

    private static String rootMsg(Throwable e) {
        Throwable t = e;
        while (t.getCause() != null) {
            t = t.getCause();
        }
        String msg = String.valueOf(t.getMessage());
        return msg.length() > 200 ? msg.substring(0, 200) : msg;
    }

    // ---------- 能力说明书（loadSkill 模式） ----------

    /**
     * 能力说明书：调数据工具前按需查阅（参数口径 / 能答什么 / 示例问法 / 输出与图表形态）。
     * 数据源 = CapabilityRegistry 的富元数据，经 CopilotToolCatalog.registryId 桥接到本链路工具面。
     */
    @Tool(name = "load_capability_guide", description = "调用数据查询工具前，先用它查看该能力的使用说明："
            + "参数怎么填、能回答什么问题、示例问法、输出与图表形态。每轮最多读 3 个。")
    public String loadCapabilityGuide(
            @ToolParam(name = "tool", required = true, description = "工具名（如 count_alarms_by_type）或中文名（如 告警类型统计）") String tool) {
        CopilotToolCatalog.ToolMeta meta = metaOf(tool);
        if (meta == null) {
            return jsonMsg("not_found", "没有这个工具：" + tool + "。可用工具："
                    + String.join("、", CopilotToolCatalog.list().stream().map(CopilotToolCatalog.ToolMeta::id).toList()));
        }
        if (++guideReads > 3) {
            return jsonMsg("budget", "本轮说明书已读满 3 个，请基于已有信息决策：直接调用工具或向用户澄清。");
        }
        String guide;
        String registryId = meta.registryId();
        if (registry != null && registryId != null) {
            var def = registry.get(registryId);
            if (def.isPresent()) {
                guide = renderGuide(meta, def.get());
            } else {
                guide = renderMetaGuide(meta, "capability 目录中未找到 " + registryId + "，以下为目录级说明");
            }
        } else {
            guide = renderMetaGuide(meta, registryId == null
                    ? "该工具暂无对应 capability 富说明书，以下为目录级说明"
                    : "capability 注册中心不可用，以下为目录级说明");
        }
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("status", "ok");
        out.put("tool", meta.id());
        out.put("guide", guide);
        return toJson(out);
    }

    /** 工具 id/中文名 -> 目录条目（不区分大小写匹配 id） */
    private static CopilotToolCatalog.ToolMeta metaOf(String tool) {
        if (tool == null || tool.isBlank()) {
            return null;
        }
        String key = tool.trim();
        return CopilotToolCatalog.list().stream()
                .filter(t -> t.id().equalsIgnoreCase(key) || t.display().equals(key))
                .findFirst().orElse(null);
    }

    /** 渲染富说明书（registry 元数据）：用途/别名/参数口径/示例问法/输出列语义/图表与时效 */
    private String renderGuide(CopilotToolCatalog.ToolMeta meta, CapabilityDefinition def) {
        StringBuilder md = new StringBuilder();
        md.append("# ").append(meta.display()).append("（").append(meta.id()).append("）\n");
        if (notBlank(def.getDescription())) {
            md.append("用途：").append(def.getDescription()).append('\n');
        }
        if (def.getAliases() != null && !def.getAliases().isEmpty()) {
            md.append("别名/关键词：").append(String.join("、", def.getAliases())).append('\n');
        }
        if (def.getParams() != null && !def.getParams().isEmpty()) {
            md.append("参数口径：\n");
            for (CapabilityDefinition.ParamDef p : def.getParams()) {
                StringBuilder line = new StringBuilder("- ").append(p.getName())
                        .append("（").append(p.getType() == null ? "string" : p.getType())
                        .append(p.isRequired() ? "，必填" : "，可选").append('）');
                if (notBlank(p.getDescription())) {
                    line.append("：").append(p.getDescription());
                }
                if (p.getDefaultValue() != null) {
                    line.append("；默认 ").append(p.getDefaultValue());
                }
                if (p.getMaxSpanDays() != null) {
                    line.append("；最大跨度 ").append(p.getMaxSpanDays()).append(" 天");
                }
                if (p.getEnumValues() != null && !p.getEnumValues().isEmpty()) {
                    line.append("；可选值：").append(String.join("|", p.getEnumValues()));
                }
                md.append(line).append('\n');
            }
        }
        if (def.getSampleQuestions() != null && !def.getSampleQuestions().isEmpty()) {
            md.append("能回答的问法示例：\n");
            def.getSampleQuestions().forEach(q -> md.append("- 「").append(q).append("」\n"));
        }
        if (def.getReturns() != null && def.getReturns().getColumns() != null
                && !def.getReturns().getColumns().isEmpty()) {
            md.append("输出（").append(def.getReturns().getShape() == null ? "table" : def.getReturns().getShape()).append("）：");
            List<String> cols = def.getReturns().getColumns().stream()
                    .map(c -> (notBlank(c.getDisplay()) ? c.getDisplay() : c.getName())
                            + (c.getSemantic() == null ? "" : "/" + c.getSemantic())
                            + (c.getUnit() == null ? "" : "(" + c.getUnit() + ")"))
                    .toList();
            md.append(String.join("、", cols)).append('\n');
        }
        if (notBlank(def.getChartHint())) {
            md.append("建议图表：").append(def.getChartHint()).append('\n');
        }
        if (def.getFreshnessPolicy() != null) {
            md.append("数据时效：").append(def.getFreshnessPolicy().getType())
                    .append("（预期延迟 ").append(def.getFreshnessPolicy().getExpectedDelayMin()).append(" 分钟）\n");
        }
        md.append("提示：说明书用于理解口径与问法，实际调用的参数名以工具定义为准。\n");
        return md.toString();
    }

    /** 降级说明书：registry 不可用 / 无映射时，基于目录条目给最小可用说明 */
    private String renderMetaGuide(CopilotToolCatalog.ToolMeta meta, String reason) {
        return "# " + meta.display() + "（" + meta.id() + "）\n"
                + "用途：" + meta.description() + "\n"
                + "（" + reason + "；参数口径见工具参数描述：时间范围默认近 7 天，车辆支持车牌/VIN 模糊。）\n";
    }

    private static boolean notBlank(String s) {
        return s != null && !s.isBlank();
    }

    // ---------- 车辆域 ----------

    /** 查车辆档案 */
    @Tool(name = "query_vehicle_info", description = "查询车辆档案信息（车牌、车队、品牌车型、电池、状态）")
    public String queryVehicleInfo(
            @ToolParam(name = "vehicle", required = true, description = "车牌号或 VIN，支持模糊") String vehicle) {
        String vin = resolveVin(vehicle);
        if (vin == null) {
            return jsonMsg("not_found", "未找到匹配车辆：" + vehicle);
        }
        if (vin.startsWith("AMBIGUOUS:")) {
            return vin.substring("AMBIGUOUS:".length());
        }
        SimQueries.Outcome oc = queries.vehicleInfo(vin, ctx);
        bridge.emitOutcome(oc, Map.of("vehicle", vehicle), "query_vehicle_info");
        return summarize(oc, "车辆档案已展示");
    }

    /** 离线车辆清单 */
    @Tool(name = "list_offline_vehicles", description = "查询离线超过 N 小时的车辆清单（默认 24 小时）")
    public String listOfflineVehicles(
            @ToolParam(name = "hours", required = false, description = "离线时长阈值（小时），默认 24") Integer hours) {
        int h = hours == null || hours <= 0 ? 24 : hours;
        SimQueries.Outcome oc = queries.offlineVehicles(h, ctx);
        bridge.emitOutcome(oc, Map.of("offline_hours>=", h), "list_offline_vehicles");
        return summarize(oc, "离线超 " + h + " 小时车辆 " + oc.table().rowCount() + " 台");
    }

    /** 车辆最后位置 */
    @Tool(name = "query_vehicle_location", description = "查询车辆最后上报位置（经纬度、城市、里程表）")
    public String queryVehicleLocation(
            @ToolParam(name = "vehicle", required = true, description = "车牌号或 VIN") String vehicle) {
        String vin = resolveVin(vehicle);
        if (vin == null) {
            return jsonMsg("not_found", "未找到匹配车辆：" + vehicle);
        }
        if (vin.startsWith("AMBIGUOUS:")) {
            return vin.substring("AMBIGUOUS:".length());
        }
        SimQueries.Outcome oc = queries.vehicleLocation(vin, ctx);
        bridge.emitOutcome(oc, Map.of("vehicle", vehicle), "query_vehicle_location");
        return summarize(oc, "最后位置已展示");
    }

    // ---------- 告警域 ----------

    /** 告警类型统计 */
    @Tool(name = "count_alarms_by_type", description = "统计时间范围内各类告警次数，可指定单车")
    public String countAlarmsByType(
            @ToolParam(name = "time_range", required = false, description = "时间范围，如 近7天/昨天/近30天，默认近7天") String timeRange,
            @ToolParam(name = "vehicle", required = false, description = "车牌号或 VIN，可空") String vehicle) {
        TimeRanges.Range range = TimeRanges.parse(timeRange, queries.baseDate());
        String vin = resolveVinOrNull(vehicle);
        if (vehicle != null && vin == null) {
            return jsonMsg("not_found", "未找到匹配车辆：" + vehicle);
        }
        if (ambiguous(vin)) {
            return vin.substring("AMBIGUOUS:".length());
        }
        SimQueries.Outcome oc = queries.alarmsByType(range, vin, ctx);
        bridge.emitOutcome(oc, argsOf(range, vehicle), "count_alarms_by_type");
        return summarize(oc, range.display() + " 告警类型统计已展示");
    }

    /** 告警明细 */
    @Tool(name = "list_alarms", description = "查询告警明细列表，可指定车辆与时间范围")
    public String listAlarms(
            @ToolParam(name = "time_range", required = false, description = "时间范围，默认近7天") String timeRange,
            @ToolParam(name = "vehicle", required = false, description = "车牌号或 VIN，可空") String vehicle,
            @ToolParam(name = "limit", required = false, description = "返回条数，默认 50，上限 200") Integer limit) {
        TimeRanges.Range range = TimeRanges.parse(timeRange, queries.baseDate());
        String vin = resolveVinOrNull(vehicle);
        if (vehicle != null && vin == null) {
            return jsonMsg("not_found", "未找到匹配车辆：" + vehicle);
        }
        if (ambiguous(vin)) {
            return vin.substring("AMBIGUOUS:".length());
        }
        int top = limit == null ? 50 : limit;
        SimQueries.Outcome oc = queries.alarmList(range, vin, top, ctx);
        bridge.emitOutcome(oc, argsOf(range, vehicle), "list_alarms");
        return summarize(oc, range.display() + " 告警明细 " + oc.table().rowCount() + " 条");
    }

    /** 车队告警对比 */
    @Tool(name = "compare_fleet_alarms", description = "对比各车队告警总量、严重告警与百车日均告警")
    public String compareFleetAlarms(
            @ToolParam(name = "time_range", required = false, description = "时间范围，默认近7天") String timeRange) {
        TimeRanges.Range range = TimeRanges.parse(timeRange, queries.baseDate());
        SimQueries.Outcome oc = queries.fleetAlarmCompare(range, ctx);
        bridge.emitOutcome(oc, argsOf(range, null), "compare_fleet_alarms");
        return summarize(oc, range.display() + " 车队告警对比已展示");
    }

    // ---------- 故障域 ----------

    /** 故障部位统计 */
    @Tool(name = "count_faults_by_part", description = "统计时间范围内各部位故障次数与未恢复数，可指定单车")
    public String countFaultsByPart(
            @ToolParam(name = "time_range", required = false, description = "时间范围，默认近7天") String timeRange,
            @ToolParam(name = "vehicle", required = false, description = "车牌号或 VIN，可空") String vehicle) {
        TimeRanges.Range range = TimeRanges.parse(timeRange, queries.baseDate());
        String vin = resolveVinOrNull(vehicle);
        if (vehicle != null && vin == null) {
            return jsonMsg("not_found", "未找到匹配车辆：" + vehicle);
        }
        if (ambiguous(vin)) {
            return vin.substring("AMBIGUOUS:".length());
        }
        SimQueries.Outcome oc = queries.faultsByPart(range, vin, ctx);
        bridge.emitOutcome(oc, argsOf(range, vehicle), "count_faults_by_part");
        return summarize(oc, range.display() + " 故障部位统计已展示");
    }

    /** 故障明细 */
    @Tool(name = "list_faults", description = "查询故障明细，可只看未恢复故障")
    public String listFaults(
            @ToolParam(name = "time_range", required = false, description = "时间范围，默认近7天") String timeRange,
            @ToolParam(name = "vehicle", required = false, description = "车牌号或 VIN，可空") String vehicle,
            @ToolParam(name = "active_only", required = false, description = "是否只看未恢复，默认 false") Boolean activeOnly) {
        TimeRanges.Range range = TimeRanges.parse(timeRange, queries.baseDate());
        String vin = resolveVinOrNull(vehicle);
        if (vehicle != null && vin == null) {
            return jsonMsg("not_found", "未找到匹配车辆：" + vehicle);
        }
        if (ambiguous(vin)) {
            return vin.substring("AMBIGUOUS:".length());
        }
        SimQueries.Outcome oc = queries.faultList(range, vin, Boolean.TRUE.equals(activeOnly), ctx);
        bridge.emitOutcome(oc, argsOf(range, vehicle), "list_faults");
        return summarize(oc, range.display() + " 故障明细 " + oc.table().rowCount() + " 条");
    }

    // ---------- 里程 / 充电域 ----------

    /** 车队里程对比 */
    @Tool(name = "compare_fleet_mileage", description = "对比各车队总里程、车均日里程与活跃车辆数")
    public String compareFleetMileage(
            @ToolParam(name = "time_range", required = false, description = "时间范围，默认近7天") String timeRange) {
        TimeRanges.Range range = TimeRanges.parse(timeRange, queries.baseDate());
        SimQueries.Outcome oc = queries.mileageByFleet(range, ctx);
        bridge.emitOutcome(oc, argsOf(range, null), "compare_fleet_mileage");
        return summarize(oc, range.display() + " 车队里程对比已展示");
    }

    /** 每日里程趋势 */
    @Tool(name = "query_mileage_daily", description = "查询每日里程与行驶时长趋势，可指定单车")
    public String queryMileageDaily(
            @ToolParam(name = "time_range", required = false, description = "时间范围，默认近7天") String timeRange,
            @ToolParam(name = "vehicle", required = false, description = "车牌号或 VIN，可空") String vehicle) {
        TimeRanges.Range range = TimeRanges.parse(timeRange, queries.baseDate());
        String vin = resolveVinOrNull(vehicle);
        if (vehicle != null && vin == null) {
            return jsonMsg("not_found", "未找到匹配车辆：" + vehicle);
        }
        if (ambiguous(vin)) {
            return vin.substring("AMBIGUOUS:".length());
        }
        SimQueries.Outcome oc = queries.mileageDaily(range, vin, ctx);
        bridge.emitOutcome(oc, argsOf(range, vehicle), "query_mileage_daily");
        return summarize(oc, range.display() + " 里程趋势已展示");
    }

    /** 充电统计 */
    @Tool(name = "query_charge_stats", description = "统计每日充电次数、电量与快充占比，可指定单车")
    public String queryChargeStats(
            @ToolParam(name = "time_range", required = false, description = "时间范围，默认近7天") String timeRange,
            @ToolParam(name = "vehicle", required = false, description = "车牌号或 VIN，可空") String vehicle) {
        TimeRanges.Range range = TimeRanges.parse(timeRange, queries.baseDate());
        String vin = resolveVinOrNull(vehicle);
        if (vehicle != null && vin == null) {
            return jsonMsg("not_found", "未找到匹配车辆：" + vehicle);
        }
        if (ambiguous(vin)) {
            return vin.substring("AMBIGUOUS:".length());
        }
        SimQueries.Outcome oc = queries.chargeStats(range, vin, ctx);
        bridge.emitOutcome(oc, argsOf(range, vehicle), "query_charge_stats");
        return summarize(oc, range.display() + " 充电统计已展示");
    }

    // ---------- 内部工具 ----------

    /** 解析车辆参数：唯一命中返回 VIN；多命中返回 AMBIGUOUS:前缀 + 候选 JSON；无命中返回 null */
    private String resolveVin(String vehicle) {
        List<Map<String, Object>> candidates = queries.resolveVehicles(vehicle, ctx);
        if (candidates.isEmpty()) {
            return null;
        }
        if (candidates.size() == 1) {
            return String.valueOf(candidates.get(0).get("vin"));
        }
        // 精确车牌命中优先
        for (Map<String, Object> c : candidates) {
            if (vehicle.trim().equalsIgnoreCase(String.valueOf(c.get("plate_no")))) {
                return String.valueOf(c.get("vin"));
            }
        }
        return "AMBIGUOUS:" + candidateJson(candidates);
    }

    private String resolveVinOrNull(String vehicle) {
        return vehicle == null || vehicle.isBlank() ? null : resolveVin(vehicle);
    }

    private boolean ambiguous(String vin) {
        return vin != null && vin.startsWith("AMBIGUOUS:");
    }

    private Map<String, Object> argsOf(TimeRanges.Range range, String vehicle) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("time_range_display", range.display());
        if (vehicle != null) {
            m.put("vehicle", vehicle);
        }
        return m;
    }

    /** 给模型的紧凑摘要：小结果全量，大结果只给前 10 行 + 总数；空结果附纠错提示（让模型自己决定换范围还是追问） */
    private String summarize(SimQueries.Outcome oc, String note) {
        Map<String, Object> out = new LinkedHashMap<>();
        boolean empty = oc.table().rowCount() == 0;
        out.put("status", empty ? "empty" : "ok");
        out.put("note", note);
        out.put("rowCount", oc.table().rowCount());
        List<String> cols = oc.def().getReturns().getColumns().stream()
                .map(c -> c.getDisplay() + (c.getUnit() == null ? "" : "(" + c.getUnit() + ")"))
                .toList();
        out.put("columns", cols);
        int limit = oc.table().rowCount() <= 20 ? oc.table().rowCount() : 10;
        List<List<Object>> rows = new java.util.ArrayList<>();
        for (int i = 0; i < limit; i++) {
            List<Object> row = new java.util.ArrayList<>();
            for (Object cell : oc.table().rows().get(i)) {
                row.add(cell == null ? null : cell.toString());
            }
            rows.add(row);
        }
        out.put("rows", rows);
        if (oc.table().rowCount() > limit) {
            out.put("truncatedNote", "仅展示前 " + limit + " 行，完整表格已推送前端");
        }
        if (empty) {
            out.put("hint", "本次查询条件下没有数据。这不是系统故障，你可以自行决定下一步："
                    + "① 换更长时间范围（如改查近 30 天）再调一次本工具；"
                    + "② 确认车辆标识/参数是否正确；"
                    + "③ 若多路都为空，如实告诉用户当前数据基准日为 " + queries.baseDate()
                    + "，该范围可能确实无上报，并建议可用的时间范围。");
        }
        // 预算动态注入：第 3 次取证起在结果里提醒收敛（对齐系统提示规则 8 的阈值）。
        // AgentScope 运行中无法插消息，工具返回值是唯一能写回模型循环的通道。
        if (bridge.receiptCount() >= 3) {
            out.put("budgetNote", "已取证 " + bridge.receiptCount() + " 次（迭代上限 6 轮）。"
                    + "请停止扩展新查询方向，基于已有证据组织最终结论；关键数据仍缺就直接说明缺口，不要继续探索。");
        }
        return toJson(out);
    }

    private String candidateJson(List<Map<String, Object>> candidates) {
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("status", "ambiguous");
        out.put("message", "找到多台匹配车辆，请用户确认是哪一台");
        out.put("candidates", candidates.stream().map(c -> {
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("plate_no", c.get("plate_no"));
            m.put("vin", c.get("vin"));
            m.put("fleet_name", c.get("fleet_name"));
            return m;
        }).toList());
        return toJson(out);
    }

    private String jsonMsg(String status, String message) {
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("status", status);
        out.put("message", message);
        return toJson(out);
    }

    private String toJson(Object v) {
        try {
            return mapper.writeValueAsString(v);
        } catch (Exception e) {
            return String.valueOf(v);
        }
    }
}

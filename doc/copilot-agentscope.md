# AgentScope 多 Agent 编排层（fleet_copilot）

> 2026-09-02 新增。独立链路，只读模拟库 dst_v2x_sim，与旧链路（data_base/device_ops + 真实库）并存。

## 架构

    用户 -> V2xCopilotAgent (Supervisor)
             |-- SupervisorRouter   (LLM 结构化路由: vehicle/alarm/fault/mileage/anomaly/cross/chat)
             |-- SpecialistAgents   (ReActAgent + Toolkit, 按域白名单工具)
             |     |-- VEHICLE: query_vehicle_info / list_offline_vehicles / query_vehicle_location
             |     |-- ALARM:   count_alarms_by_type / list_alarms / compare_fleet_alarms
             |     |-- FAULT:   count_faults_by_part / list_faults
             |     |-- MILEAGE: compare_fleet_mileage / query_mileage_daily / query_charge_stats
             |     +-- CROSS:   全部 11 个工具
             |-- CopilotTools       (@Tool 方法 -> SimQueries 确定性 SQL -> ToolResultBridge)
             +-- anomaly Workflow   (确定性三路取证: 告警统计+故障明细+里程趋势 -> 报告流式结论)

## 红线保留

1. LLM 永不产出 SQL：工具内部是模板 SQL + PreparedStatement，模型只传参数。
2. 车队 ACL 在 SimQueries 强制拼入（admin 全量，op1=F001、op2=F002），模型无法绕过。
3. AG-UI 事件顺序：TOOL_CALL_RESULT 先于 TEXT_MESSAGE_CONTENT。
4. 每次工具调用都推送表格 + 图表（CHART_SPEC，支持 bar/line/pie/area/metric_card/map），聊天框内直接渲染。

## 模拟库 dst_v2x_sim

| 表 | 内容 | 量 |
|---|---|---|
| dim_fleet / dim_vehicle | 3 车队 / 120 车 | 3 / 120 |
| fact_vehicle_status_daily | 每日在线快照+位置 | 10,041 |
| fact_mileage_daily | 日里程 | 10,041 |
| fact_trip / fact_charge | 行程 / 充电 | 39,690 / 10,224 |
| fact_alarm / fact_fault | 告警 / 故障 | 14,497 / 1,148 |
| fact_geofence_event | 围栏事件 | 9,902 |

故事线（演示用）：
- 粤BD96880（F001 第一台）：电池类故障聚集 + 里程逍渐，适合「这台车怎么回事」。
- F002 车队：疲劳驾驶告警占比约 18%（其他车队 ~5%）。
- 9 台长离线车（3 台停用 >20 天）。

重置数据：python scripts/sim/seed_sim_doris.py（幂等：drop+create+insert，固定 seed=42）。

## 关键文件

- copilot/V2xCopilotAgent.java   总控 Agent（AG-UI 入口，注册名 fleet_copilot）
- copilot/SupervisorRouter.java  结构化路由（Schema 约束，只输出 route+槽位）
- copilot/SpecialistAgents.java  领域专家工厂（Toolkit enableTools 白名单）
- copilot/CopilotTools.java      11 个 @Tool 方法
- copilot/SimQueries.java        确定性 SQL + ACL（数据基准日锡定 MAX(dt)）
- copilot/ToolResultBridge.java  工具结果 -> AG-UI 表格/图表事件桥
- scripts/sim/ddl.sql + seed_sim_doris.py   建库与种子

## 已验证场景（2026-09-02）

- 路由 -> alarm 专家 -> 工具 -> 表格/柱图 -> 流式结论 事件链完整
- 异常 Workflow 三路取证 + 报告（粤BD96880 正确识别电池绝缘故障线索）
- op1（F001）车队对比只见本车队（ACL）
- 多轮继承：「那明细呢」继承 route+车辆

## 坑

- agentscope-core 需要 json-schema-validator 2.0.0（项目原 1.5.2 抢了依赖调停，SpecificationVersion 类缺失）。
- GLM 编码端点模型必须支持 tool calling（glm-5.3-flash 已验证可用）。

-- ============================================================
-- V19: seed 24 TBOX capabilities + anomaly_explain orchestration ref
-- Capability definitions are database-native assets (single source of
-- truth = capability_definition table); no local yaml seeds anymore.
-- ============================================================
DELETE FROM capability_definition WHERE tenant_id = 'T1';

INSERT INTO capability_definition
(id, kind, tenant_id, display, description, aliases, domain, readonly, params, returns_meta,
 chart_hint, source_tables, freshness_policy, limits, cache, scopes, row_filter_policy,
 sample_questions, sql_template, status, version, owner)
VALUES
('alarm_count_by_type', 'capability', 'T1', '按类型统计告警', '按告警名称统计告警次数', '["告警统计", "告警类型统计", "哪种告警最多"]', 'alarm', 1, '[{"name": "time_range", "type": "daterange", "required": true, "max_span_days": 90, "description": "时间范围"}, {"name": "vehicle", "type": "string", "required": false, "description": "车牌号或 VIN"}, {"name": "vin_list", "type": "array<string>", "required": false, "max_items": 50}]', '{"shape": "table", "columns": [{"name": "alarm_name", "semantic": "category", "display": "告警类型"}, {"name": "alarm_count", "semantic": "metric", "display": "告警次数", "unit": "次"}]}', 'bar', '["tbox_alarm_begin_record"]', '{"type": "realtime", "expected_delay_min": 5}', '{"max_rows": 100, "timeout_ms": 15000, "max_span_days": 90}', '{"cacheable": true, "ttl_seconds": 300}', '["vehicle.alarm.read"]', 'by_org', '["哪种告警最多", "告警按类型统计"]', 'SELECT ab.alarm_name,
       COUNT(1) AS alarm_count
FROM tbox_alarm_begin_record ab
WHERE ab.begin_time >= ${time_from}
  AND ab.begin_time < ${time_to}
  AND (${vin_list_empty} OR ab.vin_code IN (${vin_list}))
  AND ab.vin_code IN (SELECT vin_code FROM basic_vehicle_info WHERE org_name IN (${acl_org_ids}))
GROUP BY ab.alarm_name
ORDER BY alarm_count DESC
LIMIT 100
', 'online', 1, '数据组'),
('alarm_list', 'capability', 'T1', '车辆告警明细', '查询指定时间范围内车辆的 TBOX 国标告警明细记录', '["告警明细", "报警记录", "告警历史", "告警列表", "哪些告警", "触发"]', 'alarm', 1, '[{"name": "time_range", "type": "daterange", "required": true, "max_span_days": 90, "description": "时间范围，如 近7天 / 本周"}, {"name": "vehicle", "type": "string", "required": false, "description": "车牌号或 VIN"}, {"name": "vin_list", "type": "array<string>", "required": false, "description": "车辆 VIN 列表", "max_items": 50}]', '{"shape": "table", "columns": [{"name": "alarm_start_time", "semantic": "category", "display": "告警开始时间"}, {"name": "plate_no", "semantic": "category", "display": "车牌号"}, {"name": "alarm_name", "semantic": "category", "display": "告警类型"}, {"name": "risk_level", "semantic": "category", "display": "风险等级"}, {"name": "alarm_value", "semantic": "category", "display": "告警值"}, {"name": "alarm_end_time", "semantic": "category", "display": "告警结束时间"}]}', 'table', '["tbox_alarm_begin_record", "tbox_alarm_end_record", "basic_vehicle_info"]', '{"type": "realtime", "expected_delay_min": 5}', '{"max_rows": 500, "timeout_ms": 15000, "max_span_days": 90}', '{"cacheable": false}', '["vehicle.alarm.read"]', 'by_org', '["近两天的告警明细", "这台车最近报了什么警"]', 'SELECT ab.begin_time AS alarm_start_time,
       COALESCE(NULLIF(ab.car_no, ''''), ab.vin_code) AS plate_no,
       ab.alarm_name,
       CONCAT(''等级'', ab.alarm_level) AS risk_level,
       ab.alarm_value,
       ae.end_time AS alarm_end_time
FROM tbox_alarm_begin_record ab
LEFT JOIN tbox_alarm_end_record ae
  ON ab.vin_code = ae.vin_code AND ab.begin_time = ae.begin_time
WHERE ab.begin_time >= ${time_from}
  AND ab.begin_time < ${time_to}
  AND (${vin_list_empty} OR ab.vin_code IN (${vin_list}))
  AND ab.vin_code IN (SELECT vin_code FROM basic_vehicle_info WHERE org_name IN (${acl_org_ids}))
ORDER BY ab.begin_time DESC
LIMIT 500
', 'online', 1, '数据组'),
('anomaly_explain', 'orchestration', 'T1', '车辆异常解释', '多源取证解释车辆异常原因（告警明细 + 类型分布 + 里程变化 联合分析）', '["为什么告警", "频繁告警", "异常解释", "怎么回事", "异常原因"]', 'alarm', 1, '[{"name": "vehicle", "type": "string", "required": true, "description": "车牌号或 VIN"}, {"name": "time_range", "type": "daterange", "required": false, "default_value": "近7天", "description": "时间范围，如 近7天 / 本周"}, {"name": "vin_list", "type": "array<string>", "required": false, "description": "车辆 VIN 列表（澄清选择/多轮继承用）", "max_items": 50}]', '{"shape": "table", "columns": [{"name": "summary", "semantic": "category", "display": "分析项"}]}', 'bar', '[]', '{"type": "realtime", "expected_delay_min": 5}', '{"max_rows": 1000, "timeout_ms": 20000}', '{"cacheable": false}', '["vehicle.alarm.read"]', 'by_fleet', '["这台车为什么频繁告警", "沪A10011最近怎么回事", "这台车异常原因"]', NULL, 'online', 1, '数据组'),
('battery_soh', 'capability', 'T1', '电池健康度 SOH', '查询车辆最新电池健康度（SOH）', '["SOH", "电池健康度", "电池寿命"]', 'battery', 1, '[{"name": "vehicle", "type": "string", "required": false, "description": "车牌号或 VIN"}, {"name": "vin_list", "type": "array<string>", "required": false, "max_items": 50}]', '{"shape": "table", "columns": [{"name": "plate_no", "semantic": "category", "display": "车牌号"}, {"name": "dev_time", "semantic": "category", "display": "上报时间"}, {"name": "battery_soh", "semantic": "metric", "display": "SOH", "unit": "%", "scale": 1}]}', 'metric_card', '["tbox_realtime_histroy_data", "basic_vehicle_info"]', '{"type": "t_plus_0", "expected_delay_min": 60}', '{"max_rows": 500, "timeout_ms": 15000}', '{"cacheable": false}', '["vehicle.battery.read"]', 'by_org', '["这台车电池 SOH 多少", "电池健康度怎么样"]', 'SELECT plate_no, dev_time, battery_soh
FROM (
    SELECT COALESCE(b.car_no, t.vin_code) AS plate_no,
           t.dev_time,
           t.battery_soh,
           ROW_NUMBER() OVER (PARTITION BY t.vin_code ORDER BY t.dev_time DESC) AS rn
    FROM tbox_realtime_histroy_data t
    JOIN basic_vehicle_info b ON t.vin_code = b.vin_code
    WHERE t.battery_soh IS NOT NULL
      AND (${vin_list_empty} OR t.vin_code IN (${vin_list}))
      AND b.org_name IN (${acl_org_ids})
) r
WHERE rn = 1
LIMIT 500
', 'online', 1, '数据组'),
('charge_daily', 'capability', 'T1', '充电日统计', '统计车辆每日充电次数/电量/时长（按单次充电记录聚合）', '["充电统计", "充电记录", "每日充电", "充了多少电", "充电次数"]', 'charge', 1, '[{"name": "time_range", "type": "daterange", "required": false, "default_value": "近7天", "max_span_days": 31, "description": "时间范围"}, {"name": "vehicle", "type": "string", "required": false, "description": "车牌号或 VIN"}, {"name": "vin_list", "type": "array<string>", "required": false, "max_items": 20}]', '{"shape": "table", "columns": [{"name": "query_date", "semantic": "category", "display": "日期"}, {"name": "plate_no", "semantic": "category", "display": "车牌号"}, {"name": "charge_times", "semantic": "metric", "display": "充电次数", "unit": "次"}, {"name": "charge_total", "semantic": "metric", "display": "充电电量", "unit": "kWh", "scale": 1}, {"name": "charge_duration", "semantic": "metric", "display": "充电时长", "unit": "分钟", "scale": 0}]}', 'bar', '["tbox_charge_record"]', '{"type": "t_plus_1", "expected_delay_min": 1440}', '{"max_rows": 1000, "timeout_ms": 15000, "max_span_days": 31}', '{"cacheable": true, "ttl_seconds": 600}', '["vehicle.charge.read"]', 'by_org', '["这台车近七天充了多少电", "每天充电情况"]', 'SELECT t.begin_date AS query_date,
       COALESCE(NULLIF(t.car_no, ''''), t.vin_code) AS plate_no,
       COUNT(1) AS charge_times,
       ROUND(SUM(t.charge_electricity), 1) AS charge_total,
       ROUND(SUM(t.charge_time) / 60, 0) AS charge_duration
FROM tbox_charge_record t
WHERE t.begin_date >= ${time_from}
  AND t.begin_date < ${time_to}
  AND (${vin_list_empty} OR t.vin_code IN (${vin_list}))
  AND t.vin_code IN (SELECT vin_code FROM basic_vehicle_info WHERE org_name IN (${acl_org_ids}))
GROUP BY t.begin_date, plate_no
ORDER BY t.begin_date DESC, plate_no
LIMIT 1000
', 'online', 1, '数据组'),
('charge_detail', 'capability', 'T1', '单次充电明细', '查询每次充电的起止时间、SOC、电量与时长', '["单次充电", "充电明细", "充电起止", "每次充电"]', 'charge', 1, '[{"name": "time_range", "type": "daterange", "required": false, "default_value": "近7天", "max_span_days": 31, "description": "时间范围"}, {"name": "vehicle", "type": "string", "required": false, "description": "车牌号或 VIN"}, {"name": "vin_list", "type": "array<string>", "required": false, "max_items": 20}]', '{"shape": "table", "columns": [{"name": "begin_time", "semantic": "category", "display": "开始时间"}, {"name": "plate_no", "semantic": "category", "display": "车牌号"}, {"name": "begin_soc", "semantic": "metric", "display": "起始 SOC", "unit": "%"}, {"name": "end_time", "semantic": "category", "display": "结束时间"}, {"name": "end_soc", "semantic": "metric", "display": "结束 SOC", "unit": "%"}, {"name": "charge_capacity", "semantic": "metric", "display": "充电电量", "unit": "kWh", "scale": 1}, {"name": "charge_duration", "semantic": "metric", "display": "充电时长", "unit": "分钟", "scale": 0}]}', 'table', '["tbox_charging_begin_record", "tbox_charging_end_record"]', '{"type": "t_plus_1", "expected_delay_min": 1440}', '{"max_rows": 1000, "timeout_ms": 15000, "max_span_days": 31}', '{"cacheable": true, "ttl_seconds": 600}', '["vehicle.charge.read"]', 'by_org', '["这台车每次充电多久", "单次充电明细"]', 'SELECT b.begin_time,
       COALESCE(NULLIF(b.car_no, ''''), b.vin_code) AS plate_no,
       b.begin_soc,
       e.end_time,
       e.end_soc,
       ROUND(e.charge_capacity, 1) AS charge_capacity,
       ROUND(e.charge_duration / 60, 0) AS charge_duration
FROM tbox_charging_begin_record b
JOIN tbox_charging_end_record e ON b.vin_code = e.vin_code AND b.begin_time = e.begin_time
WHERE b.begin_time >= ${time_from}
  AND b.begin_time < ${time_to}
  AND (${vin_list_empty} OR b.vin_code IN (${vin_list}))
  AND b.vin_code IN (SELECT vin_code FROM basic_vehicle_info WHERE org_name IN (${acl_org_ids}))
ORDER BY b.begin_time DESC
LIMIT 1000
', 'online', 1, '数据组'),
('cold_chain_monitor', 'capability', 'T1', '冷链温度监控', '查询冷藏车冷链探头温度（仅 reefer_flag=1 车辆）', '["冷链温度", "冷藏车温度", "温度监控"]', 'coldchain', 1, '[{"name": "vehicle", "type": "string", "required": false, "description": "车牌号或 VIN"}, {"name": "vin_list", "type": "array<string>", "required": false, "max_items": 50}]', '{"shape": "table", "columns": [{"name": "plate_no", "semantic": "category", "display": "车牌号"}, {"name": "dev_time", "semantic": "category", "display": "上报时间"}, {"name": "temp1", "semantic": "metric", "display": "探头1温度", "unit": "℃", "scale": 1}, {"name": "temp2", "semantic": "metric", "display": "探头2温度", "unit": "℃", "scale": 1}, {"name": "temp3", "semantic": "metric", "display": "探头3温度", "unit": "℃", "scale": 1}, {"name": "temp4", "semantic": "metric", "display": "探头4温度", "unit": "℃", "scale": 1}]}', 'line', '["tbox_realtime_vehicle_data", "basic_vehicle_info"]', '{"type": "realtime", "expected_delay_min": 5}', '{"max_rows": 500, "timeout_ms": 15000}', '{"cacheable": false}', '["vehicle.coldchain.read"]', 'by_org', '["冷藏车温度多少", "冷链温度有没有越限"]', 'SELECT COALESCE(b.car_no, t.vin_code) AS plate_no,
       t.dev_time,
       t.cold_chain_temperature1 AS temp1,
       t.cold_chain_temperature2 AS temp2,
       t.cold_chain_temperature3 AS temp3,
       t.cold_chain_temperature4 AS temp4
FROM tbox_realtime_vehicle_data t
JOIN basic_vehicle_info b ON t.vin_code = b.vin_code
WHERE b.reefer_flag = ''1''
  AND (${vin_list_empty} OR t.vin_code IN (${vin_list}))
  AND b.org_name IN (${acl_org_ids})
ORDER BY t.dev_time DESC
LIMIT 500
', 'online', 1, '数据组'),
('dangerous_driving_rank', 'capability', 'T1', '危险驾驶排行', '按驾驶行为告警次数/时长排行', '["危险驾驶", "驾驶排行", "谁开车最猛"]', 'safety', 1, '[{"name": "time_range", "type": "daterange", "required": true, "max_span_days": 90, "description": "时间范围"}, {"name": "vehicle", "type": "string", "required": false, "description": "车牌号或 VIN"}, {"name": "vin_list", "type": "array<string>", "required": false, "max_items": 50}]', '{"shape": "table", "columns": [{"name": "plate_no", "semantic": "category", "display": "车牌号"}, {"name": "alarm_count", "semantic": "metric", "display": "告警次数", "unit": "次"}, {"name": "total_duration", "semantic": "metric", "display": "总持续时长", "unit": "秒", "scale": 0}]}', 'bar', '["tbox_driving_behavior_alarm_record"]', '{"type": "realtime", "expected_delay_min": 5}', '{"max_rows": 100, "timeout_ms": 15000, "max_span_days": 90}', '{"cacheable": true, "ttl_seconds": 300}', '["vehicle.safety.read"]', 'by_org', '["危险驾驶排行", "谁最近急加速最多"]', 'SELECT COALESCE(NULLIF(d.car_no, ''''), d.vin_code) AS plate_no,
       COUNT(1) AS alarm_count,
       SUM(d.continue_time) AS total_duration
FROM tbox_driving_behavior_alarm_record d
WHERE d.begin_time >= ${time_from}
  AND d.begin_time < ${time_to}
  AND (${vin_list_empty} OR d.vin_code IN (${vin_list}))
  AND d.vin_code IN (SELECT vin_code FROM basic_vehicle_info WHERE org_name IN (${acl_org_ids}))
GROUP BY plate_no
ORDER BY alarm_count DESC
LIMIT 100
', 'online', 1, '数据组'),
('driving_behavior_alarm_list', 'capability', 'T1', '驾驶行为告警明细', '查询车辆急加速、急减速、急转弯、超速等驾驶行为告警', '["驾驶行为", "急加速", "急减速", "急转弯", "超速", "危险驾驶明细"]', 'safety', 1, '[{"name": "time_range", "type": "daterange", "required": true, "max_span_days": 90, "description": "时间范围"}, {"name": "vehicle", "type": "string", "required": false, "description": "车牌号或 VIN"}, {"name": "vin_list", "type": "array<string>", "required": false, "max_items": 50}]', '{"shape": "table", "columns": [{"name": "begin_time", "semantic": "category", "display": "开始时间"}, {"name": "plate_no", "semantic": "category", "display": "车牌号"}, {"name": "alarm_type", "semantic": "category", "display": "告警类型"}, {"name": "begin_speed", "semantic": "metric", "display": "开始速度", "unit": "km/h", "scale": 1}, {"name": "end_speed", "semantic": "metric", "display": "结束速度", "unit": "km/h", "scale": 1}, {"name": "continue_time", "semantic": "metric", "display": "持续时长", "unit": "秒", "scale": 0}]}', 'table', '["tbox_driving_behavior_alarm_record"]', '{"type": "realtime", "expected_delay_min": 5}', '{"max_rows": 500, "timeout_ms": 15000, "max_span_days": 90}', '{"cacheable": false}', '["vehicle.safety.read"]', 'by_org', '["最近有哪些驾驶行为告警", "这台车有没有急加速"]', 'SELECT d.begin_time,
       COALESCE(NULLIF(d.car_no, ''''), d.vin_code) AS plate_no,
       d.alarm_type,
       ROUND(d.begin_speed, 1) AS begin_speed,
       ROUND(d.end_speed, 1) AS end_speed,
       d.continue_time
FROM tbox_driving_behavior_alarm_record d
WHERE d.begin_time >= ${time_from}
  AND d.begin_time < ${time_to}
  AND (${vin_list_empty} OR d.vin_code IN (${vin_list}))
  AND d.vin_code IN (SELECT vin_code FROM basic_vehicle_info WHERE org_name IN (${acl_org_ids}))
ORDER BY d.begin_time DESC
LIMIT 500
', 'online', 1, '数据组'),
('energy_consumption_per_100km', 'capability', 'T1', '百公里能耗分析', '按行程分析车辆百公里平均能耗', '["百公里能耗", "能耗分析", "每百公里耗电"]', 'energy', 1, '[{"name": "time_range", "type": "daterange", "required": false, "default_value": "近7天", "max_span_days": 31, "description": "时间范围"}, {"name": "vehicle", "type": "string", "required": false, "description": "车牌号或 VIN"}, {"name": "vin_list", "type": "array<string>", "required": false, "max_items": 20}]', '{"shape": "table", "columns": [{"name": "plate_no", "semantic": "category", "display": "车牌号"}, {"name": "trip_count", "semantic": "metric", "display": "行程数", "unit": "次"}, {"name": "total_mileage", "semantic": "metric", "display": "总里程", "unit": "km", "scale": 1}, {"name": "total_charge", "semantic": "metric", "display": "总耗电", "unit": "kWh", "scale": 1}, {"name": "avg_energy_per_100km", "semantic": "metric", "display": "平均百公里能耗", "unit": "kWh/100km", "scale": 1}]}', 'bar', '["tbox_trip_record"]', '{"type": "t_plus_1", "expected_delay_min": 1440}', '{"max_rows": 500, "timeout_ms": 15000, "max_span_days": 31}', '{"cacheable": true, "ttl_seconds": 600}', '["vehicle.trip.read"]', 'by_org', '["这台车百公里能耗多少", "平均电耗怎么样"]', 'SELECT COALESCE(NULLIF(t.car_no, ''''), t.vin_code) AS plate_no,
       COUNT(1) AS trip_count,
       ROUND(SUM(t.drive_mileage), 1) AS total_mileage,
       ROUND(SUM(t.trip_charge), 1) AS total_charge,
       ROUND(SUM(t.trip_charge) / NULLIF(SUM(t.drive_mileage), 0) * 100, 1) AS avg_energy_per_100km
FROM tbox_trip_record t
WHERE t.begin_date >= ${time_from}
  AND t.begin_date < ${time_to}
  AND (${vin_list_empty} OR t.vin_code IN (${vin_list}))
  AND t.vin_code IN (SELECT vin_code FROM basic_vehicle_info WHERE org_name IN (${acl_org_ids}))
GROUP BY plate_no
ORDER BY avg_energy_per_100km DESC
LIMIT 500
', 'online', 1, '数据组'),
('fault_count_by_part', 'capability', 'T1', '故障统计（按部件）', '按故障部件聚合故障次数', '["故障部件统计", "故障聚合", "哪个部件故障多"]', 'fault', 1, '[{"name": "time_range", "type": "daterange", "required": false, "default_value": "近7天", "max_span_days": 90, "description": "时间范围"}, {"name": "vehicle", "type": "string", "required": false, "description": "车牌号或 VIN"}, {"name": "vin_list", "type": "array<string>", "required": false, "max_items": 50}]', '{"shape": "table", "columns": [{"name": "fault_part", "semantic": "category", "display": "部件"}, {"name": "fault_count", "semantic": "metric", "display": "故障次数", "unit": "次"}]}', 'pie', '["tbox_fault_history"]', '{"type": "realtime", "expected_delay_min": 30}', '{"max_rows": 100, "timeout_ms": 15000, "max_span_days": 90}', '{"cacheable": true, "ttl_seconds": 600}', '["vehicle.fault.read"]', 'by_org', '["哪个部件故障最多", "故障按部件统计"]', 'SELECT COALESCE(NULLIF(f.fault_part, ''''), ''未标注'') AS fault_part,
       COUNT(1) AS fault_count
FROM tbox_fault_history f
WHERE f.data_time >= ${time_from}
  AND f.data_time < ${time_to}
  AND (${vin_list_empty} OR f.vin_code IN (${vin_list}))
  AND f.vin_code IN (SELECT vin_code FROM basic_vehicle_info WHERE org_name IN (${acl_org_ids}))
GROUP BY fault_part
ORDER BY fault_count DESC
LIMIT 100
', 'online', 1, '数据组'),
('fault_list', 'capability', 'T1', '车辆故障明细', '查询指定时间范围内车辆的故障明细记录', '["故障明细", "故障记录", "故障历史", "故障列表"]', 'fault', 1, '[{"name": "time_range", "type": "daterange", "required": false, "default_value": "近7天", "max_span_days": 90, "description": "时间范围"}, {"name": "vehicle", "type": "string", "required": false, "description": "车牌号或 VIN"}, {"name": "vin_list", "type": "array<string>", "required": false, "max_items": 50}]', '{"shape": "table", "columns": [{"name": "data_time", "semantic": "category", "display": "故障时间"}, {"name": "plate_no", "semantic": "category", "display": "车牌号"}, {"name": "fault_name", "semantic": "category", "display": "故障名称"}, {"name": "fault_part", "semantic": "category", "display": "部件"}, {"name": "fault_reason", "semantic": "category", "display": "故障原因"}]}', 'table', '["tbox_fault_history"]', '{"type": "realtime", "expected_delay_min": 30}', '{"max_rows": 500, "timeout_ms": 15000, "max_span_days": 90}', '{"cacheable": false}', '["vehicle.fault.read"]', 'by_org', '["近两天的故障明细", "这台车报了什么故障"]', 'SELECT f.data_time,
       COALESCE(NULLIF(f.car_no, ''''), f.vin_code) AS plate_no,
       f.fault_name,
       COALESCE(NULLIF(f.fault_part, ''''), ''未标注'') AS fault_part,
       COALESCE(f.fault_reason, '''') AS fault_reason
FROM tbox_fault_history f
WHERE f.data_time >= ${time_from}
  AND f.data_time < ${time_to}
  AND (${vin_list_empty} OR f.vin_code IN (${vin_list}))
  AND f.vin_code IN (SELECT vin_code FROM basic_vehicle_info WHERE org_name IN (${acl_org_ids}))
ORDER BY f.data_time DESC
LIMIT 500
', 'online', 1, '数据组'),
('fence_event_list', 'capability', 'T1', '围栏进出事件', '查询车辆进出电子围栏的事件记录（0=驶入 / 1=驶出 / 2=滞留）', '["围栏事件", "进出围栏", "围栏报警", "进出记录"]', 'geofence', 1, '[{"name": "time_range", "type": "daterange", "required": false, "default_value": "近7天", "max_span_days": 90, "description": "时间范围"}, {"name": "vehicle", "type": "string", "required": false, "description": "车牌号或 VIN"}, {"name": "vin_list", "type": "array<string>", "required": false, "max_items": 50}]', '{"shape": "table", "columns": [{"name": "event_time", "semantic": "category", "display": "事件时间"}, {"name": "plate_no", "semantic": "category", "display": "车牌号"}, {"name": "fence_name", "semantic": "category", "display": "围栏名称"}, {"name": "trigger_type", "semantic": "category", "display": "进出类型"}]}', 'table', '["log_fence_event"]', '{"type": "realtime", "expected_delay_min": 30}', '{"max_rows": 500, "timeout_ms": 15000, "max_span_days": 90}', '{"cacheable": false}', '["vehicle.fence.read"]', 'by_org', '["近7天围栏进出情况", "哪些车进出过围栏"]', 'SELECT e.event_time,
       COALESCE(NULLIF(e.car_no, ''''), e.vin_code) AS plate_no,
       e.fence_name,
       CASE e.trigger_type
         WHEN ''0'' THEN ''驶入''
         WHEN ''1'' THEN ''驶出''
         WHEN ''2'' THEN ''滞留''
         ELSE e.trigger_type
       END AS trigger_type
FROM log_fence_event e
WHERE e.event_time >= ${time_from}
  AND e.event_time < ${time_to}
  AND (${vin_list_empty} OR e.vin_code IN (${vin_list}))
  AND e.vin_code IN (SELECT vin_code FROM basic_vehicle_info WHERE org_name IN (${acl_org_ids}))
ORDER BY e.event_time DESC
LIMIT 500
', 'online', 1, '数据组'),
('hourly_mileage', 'capability', 'T1', '小时里程分布', '统计车辆指定日期逐小时里程分布曲线', '["小时里程", "每小时里程", "几点跑得多", "里程曲线"]', 'mileage', 1, '[{"name": "time_range", "type": "daterange", "required": false, "default_value": "今天", "max_span_days": 7, "description": "日期，如 今天 / 昨天"}, {"name": "vehicle", "type": "string", "required": false, "description": "车牌号或 VIN"}, {"name": "vin_list", "type": "array<string>", "required": false, "max_items": 20}]', '{"shape": "table", "columns": [{"name": "window_hour", "semantic": "category", "display": "小时"}, {"name": "mileage", "semantic": "metric", "display": "里程", "unit": "km", "scale": 1}]}', 'line', '["vehicle_mileage_summary_hourly"]', '{"type": "realtime", "expected_delay_min": 60}', '{"max_rows": 500, "timeout_ms": 15000, "max_span_days": 7}', '{"cacheable": true, "ttl_seconds": 300}', '["vehicle.mileage.read"]', 'by_org', '["今天每小时里程分布", "几点跑得最多"]', 'SELECT DATE_FORMAT(h.window_hour, ''%H:00'') AS window_hour,
       ROUND(SUM(h.mileage), 1) AS mileage
FROM vehicle_mileage_summary_hourly h
WHERE h.window_hour >= ${time_from}
  AND h.window_hour < ${time_to}
  AND (${vin_list_empty} OR h.vin IN (${vin_list}))
  AND h.vin IN (SELECT vin_code FROM basic_vehicle_info WHERE org_name IN (${acl_org_ids}))
GROUP BY DATE_FORMAT(h.window_hour, ''%H:00'')
ORDER BY window_hour
LIMIT 500
', 'online', 1, '数据组'),
('lock_status_query', 'capability', 'T1', '锁车状态查询', '查询车辆最新锁车状态（lock_status / lock_flag）', '["锁车状态", "有没有锁车", "锁车查询"]', 'control', 1, '[{"name": "vehicle", "type": "string", "required": false, "description": "车牌号或 VIN"}, {"name": "vin_list", "type": "array<string>", "required": false, "max_items": 50}]', '{"shape": "table", "columns": [{"name": "plate_no", "semantic": "category", "display": "车牌号"}, {"name": "dev_time", "semantic": "category", "display": "上报时间"}, {"name": "lock_status", "semantic": "category", "display": "锁车状态"}, {"name": "lock_flag", "semantic": "category", "display": "锁车标识"}]}', 'table', '["tbox_realtime_histroy_data", "basic_vehicle_info"]', '{"type": "realtime", "expected_delay_min": 5}', '{"max_rows": 500, "timeout_ms": 15000}', '{"cacheable": false}', '["vehicle.control.read"]', 'by_org', '["这台车锁了吗", "锁车状态查询"]', 'SELECT plate_no, dev_time, lock_status, lock_flag
FROM (
    SELECT COALESCE(b.car_no, t.vin_code) AS plate_no,
           t.dev_time,
           t.lock_status,
           t.lock_flag,
           ROW_NUMBER() OVER (PARTITION BY t.vin_code ORDER BY t.dev_time DESC) AS rn
    FROM tbox_realtime_histroy_data t
    JOIN basic_vehicle_info b ON t.vin_code = b.vin_code
    WHERE (${vin_list_empty} OR t.vin_code IN (${vin_list}))
      AND b.org_name IN (${acl_org_ids})
) r
WHERE rn = 1
LIMIT 500
', 'online', 1, '数据组'),
('mileage_by_fleet', 'capability', 'T1', '各车队里程对比', '按车队统计每日总里程，用于横向对比', '["车队里程", "里程对比", "各车队跑了多少", "车队排名"]', 'mileage', 1, '[{"name": "time_range", "type": "daterange", "required": true, "max_span_days": 31, "description": "时间范围，如 近7天 / 本月"}, {"name": "vehicle", "type": "string", "required": false, "description": "车牌号或 VIN"}, {"name": "vin_list", "type": "array<string>", "required": false, "max_items": 20}]', '{"shape": "table", "columns": [{"name": "stat_date", "semantic": "category", "display": "日期"}, {"name": "fleet_name", "semantic": "category", "display": "车队"}, {"name": "total_mileage", "semantic": "metric", "display": "总里程", "unit": "km", "scale": 1}]}', 'bar', '["vehicle_mileage_data", "basic_vehicle_info"]', '{"type": "realtime", "expected_delay_min": 10}', '{"max_rows": 500, "timeout_ms": 20000, "max_span_days": 31}', '{"cacheable": true, "ttl_seconds": 300}', '["vehicle.mileage.read"]', 'by_org', '["各车队最近跑了多少公里", "哪个车队里程最多"]', 'SELECT m.stat_date,
       b.org_name AS fleet_name,
       ROUND(SUM(m.daily_mileage), 1) AS total_mileage
FROM (
    SELECT DATE(data_time) AS stat_date,
           vin,
           ROUND(MAX(mileage_total) - MIN(mileage_total), 1) AS daily_mileage
    FROM vehicle_mileage_data
    WHERE data_time >= ${time_from}
      AND data_time < ${time_to}
      AND cmd = 2
      AND (${vin_list_empty} OR vin IN (${vin_list}))
      AND vin IN (SELECT vin_code FROM basic_vehicle_info WHERE org_name IN (${acl_org_ids}))
    GROUP BY DATE(data_time), vin
) m
JOIN basic_vehicle_info b ON m.vin = b.vin_code
WHERE b.org_name IN (${acl_org_ids})
GROUP BY m.stat_date, b.org_name
ORDER BY m.stat_date, total_mileage DESC
LIMIT 500
', 'online', 1, '数据组'),
('mileage_daily', 'capability', 'T1', '车辆日里程统计', '统计车辆每日行驶里程（采集器累计里程差值，仅取 cmd=2 整车数据上报）', '["日里程", "每日里程", "里程趋势", "行驶里程", "每天跑多少", "多少公里"]', 'mileage', 1, '[{"name": "time_range", "type": "daterange", "required": true, "max_span_days": 31, "description": "时间范围，如 近7天 / 本周"}, {"name": "vehicle", "type": "string", "required": false, "description": "车牌号或 VIN"}, {"name": "vin_list", "type": "array<string>", "required": false, "description": "车辆 VIN 列表", "max_items": 20}]', '{"shape": "table", "columns": [{"name": "stat_date", "semantic": "category", "display": "日期"}, {"name": "vin", "semantic": "category", "display": "VIN"}, {"name": "daily_mileage", "semantic": "metric", "display": "日里程", "unit": "km", "scale": 1}]}', 'line', '["vehicle_mileage_data"]', '{"type": "realtime", "expected_delay_min": 10}', '{"max_rows": 1000, "timeout_ms": 20000, "max_span_days": 31}', '{"cacheable": true, "ttl_seconds": 300}', '["vehicle.mileage.read"]', 'by_org', '["这台车近七天每日里程", "里程趋势怎么样"]', 'SELECT DATE(m.data_time) AS stat_date,
       m.vin,
       ROUND(MAX(m.mileage_total) - MIN(m.mileage_total), 1) AS daily_mileage
FROM vehicle_mileage_data m
WHERE m.data_time >= ${time_from}
  AND m.data_time < ${time_to}
  AND m.cmd = 2
  AND (${vin_list_empty} OR m.vin IN (${vin_list}))
  AND m.vin IN (SELECT vin_code FROM basic_vehicle_info WHERE org_name IN (${acl_org_ids}))
GROUP BY DATE(m.data_time), m.vin
HAVING daily_mileage >= 0 AND daily_mileage < 2000
ORDER BY stat_date, m.vin
LIMIT 1000
', 'online', 1, '数据组'),
('offline_vehicle_list', 'capability', 'T1', '离线车辆清单', '查询离线超过指定时长的车辆清单', '["离线车辆", "掉线车辆", "离线超过", "失联车辆"]', 'online', 1, '[{"name": "offline_hours", "type": "number", "required": false, "default_value": 24, "description": "离线时长阈值（小时）"}]', '{"shape": "table", "columns": [{"name": "plate_no", "semantic": "category", "display": "车牌号"}, {"name": "fleet_name", "semantic": "category", "display": "车队"}, {"name": "last_report_time", "semantic": "category", "display": "最近上报时间"}, {"name": "offline_hours", "semantic": "metric", "display": "离线时长", "unit": "小时", "scale": 1}]}', 'table', '["tbox_realtime_vehicle_data", "basic_vehicle_info"]', '{"type": "realtime", "expected_delay_min": 5}', '{"max_rows": 500, "timeout_ms": 15000}', '{"cacheable": false}', '["vehicle.status.read"]', 'by_org', '["离线超24小时的车有哪些", "哪些车掉线了"]', 'SELECT COALESCE(b.car_no, t.vin_code) AS plate_no,
       b.org_name AS fleet_name,
       t.dev_time AS last_report_time,
       ROUND(TIMESTAMPDIFF(MINUTE, t.dev_time, NOW()) / 60, 1) AS offline_hours
FROM tbox_realtime_vehicle_data t
LEFT JOIN basic_vehicle_info b ON t.vin_code = b.vin_code
WHERE t.dev_time < DATE_SUB(NOW(), INTERVAL ${offline_hours} HOUR)
  AND t.vin_code IN (SELECT vin_code FROM basic_vehicle_info WHERE org_name IN (${acl_org_ids}))
ORDER BY t.dev_time ASC
LIMIT 500
', 'online', 1, '数据组'),
('online_rate_daily', 'capability', 'T1', '在线率统计', '按天/车队统计车辆在线率', '["在线率", "在线统计", "每天在线率"]', 'online', 1, '[{"name": "time_range", "type": "daterange", "required": true, "max_span_days": 31, "description": "时间范围"}, {"name": "vehicle", "type": "string", "required": false, "description": "车牌号或 VIN"}, {"name": "vin_list", "type": "array<string>", "required": false, "max_items": 50}]', '{"shape": "table", "columns": [{"name": "query_date", "semantic": "category", "display": "日期"}, {"name": "fleet_name", "semantic": "category", "display": "车队"}, {"name": "online_rate", "semantic": "metric", "display": "在线率", "unit": "%", "scale": 2}, {"name": "vehicle_count", "semantic": "metric", "display": "车辆数", "unit": "台"}]}', 'line', '["tbox_online_day"]', '{"type": "t_plus_1", "expected_delay_min": 1440}', '{"max_rows": 500, "timeout_ms": 15000, "max_span_days": 31}', '{"cacheable": true, "ttl_seconds": 600}', '["vehicle.status.read"]', 'by_org', '["各车队在线率如何", "最近在线率趋势"]', 'SELECT o.query_date,
       o.org_name AS fleet_name,
       ROUND(SUM(o.online_duration) / 86400 / COUNT(DISTINCT o.vehicle_id) * 100, 2) AS online_rate,
       COUNT(DISTINCT o.vehicle_id) AS vehicle_count
FROM tbox_online_day o
WHERE o.query_date >= ${time_from}
  AND o.query_date < ${time_to}
  AND (${vin_list_empty} OR o.vin_code IN (${vin_list}))
  AND o.vin_code IN (SELECT vin_code FROM basic_vehicle_info WHERE org_name IN (${acl_org_ids}))
GROUP BY o.query_date, o.org_name
ORDER BY o.query_date DESC, fleet_name
LIMIT 500
', 'online', 1, '数据组'),
('trip_detail', 'capability', 'T1', '行程明细统计', '查询车辆每次点火到熄火的行程明细', '["行程明细", "每次行程", "行程记录", "跑了几趟"]', 'trip', 1, '[{"name": "time_range", "type": "daterange", "required": false, "default_value": "近7天", "max_span_days": 31, "description": "时间范围，如 近7天 / 本周"}, {"name": "vehicle", "type": "string", "required": false, "description": "车牌号或 VIN"}, {"name": "vin_list", "type": "array<string>", "required": false, "max_items": 20}]', '{"shape": "table", "columns": [{"name": "begin_date", "semantic": "category", "display": "日期"}, {"name": "plate_no", "semantic": "category", "display": "车牌号"}, {"name": "launch_time", "semantic": "category", "display": "点火时间"}, {"name": "flameout_time", "semantic": "category", "display": "熄火时间"}, {"name": "drive_mileage", "semantic": "metric", "display": "行驶里程", "unit": "km", "scale": 1}, {"name": "drive_duration", "semantic": "metric", "display": "行驶时长", "unit": "分钟", "scale": 0}, {"name": "avg_speed", "semantic": "metric", "display": "平均速度", "unit": "km/h", "scale": 1}]}', 'table', '["tbox_trip_record"]', '{"type": "t_plus_1", "expected_delay_min": 1440}', '{"max_rows": 1000, "timeout_ms": 15000, "max_span_days": 31}', '{"cacheable": true, "ttl_seconds": 600}', '["vehicle.trip.read"]', 'by_org', '["这台车近七天每次行程", "每天跑了哪些行程"]', 'SELECT t.begin_date,
       COALESCE(NULLIF(t.car_no, ''''), t.vin_code) AS plate_no,
       t.launch_time,
       t.flameout_time,
       ROUND(t.drive_mileage, 1) AS drive_mileage,
       ROUND(t.drive_duration / 60, 0) AS drive_duration,
       ROUND(t.avg_speed, 1) AS avg_speed
FROM tbox_trip_record t
WHERE t.begin_date >= ${time_from}
  AND t.begin_date < ${time_to}
  AND (${vin_list_empty} OR t.vin_code IN (${vin_list}))
  AND t.vin_code IN (SELECT vin_code FROM basic_vehicle_info WHERE org_name IN (${acl_org_ids}))
ORDER BY t.begin_date DESC, plate_no
LIMIT 1000
', 'online', 1, '数据组'),
('trip_energy', 'capability', 'T1', '单次行程能耗', '分析单次行程耗电量与百公里平均能耗', '["行程能耗", "单次能耗", "百公里能耗", "每趟耗多少电"]', 'energy', 1, '[{"name": "time_range", "type": "daterange", "required": false, "default_value": "近7天", "max_span_days": 31, "description": "时间范围"}, {"name": "vehicle", "type": "string", "required": false, "description": "车牌号或 VIN"}, {"name": "vin_list", "type": "array<string>", "required": false, "max_items": 20}]', '{"shape": "table", "columns": [{"name": "begin_date", "semantic": "category", "display": "日期"}, {"name": "plate_no", "semantic": "category", "display": "车牌号"}, {"name": "drive_mileage", "semantic": "metric", "display": "行驶里程", "unit": "km", "scale": 1}, {"name": "trip_charge", "semantic": "metric", "display": "耗电量", "unit": "kWh", "scale": 1}, {"name": "avg_charge", "semantic": "metric", "display": "百公里能耗", "unit": "kWh/100km", "scale": 1}]}', 'bar', '["tbox_trip_record"]', '{"type": "t_plus_1", "expected_delay_min": 1440}', '{"max_rows": 1000, "timeout_ms": 15000, "max_span_days": 31}', '{"cacheable": true, "ttl_seconds": 600}', '["vehicle.trip.read"]', 'by_org', '["这台车单次行程能耗如何", "哪趟车最耗电"]', 'SELECT t.begin_date,
       COALESCE(NULLIF(t.car_no, ''''), t.vin_code) AS plate_no,
       ROUND(t.drive_mileage, 1) AS drive_mileage,
       ROUND(t.trip_charge, 1) AS trip_charge,
       ROUND(t.avg_charge, 1) AS avg_charge
FROM tbox_trip_record t
WHERE t.begin_date >= ${time_from}
  AND t.begin_date < ${time_to}
  AND (${vin_list_empty} OR t.vin_code IN (${vin_list}))
  AND t.vin_code IN (SELECT vin_code FROM basic_vehicle_info WHERE org_name IN (${acl_org_ids}))
ORDER BY t.avg_charge DESC
LIMIT 1000
', 'online', 1, '数据组'),
('vehicle_last_location', 'capability', 'T1', '车辆最后位置', '查询车辆最新上报位置（含逆地理信息）', '["最后位置", "车辆位置", "在哪", "当前位置"]', 'location', 1, '[{"name": "vehicle", "type": "string", "required": false, "description": "车牌号或 VIN"}, {"name": "vin_list", "type": "array<string>", "required": false, "max_items": 50}]', '{"shape": "table", "columns": [{"name": "plate_no", "semantic": "category", "display": "车牌号"}, {"name": "lng", "semantic": "geo_lng", "display": "经度"}, {"name": "lat", "semantic": "geo_lat", "display": "纬度"}, {"name": "province_name", "semantic": "category", "display": "省"}, {"name": "city_name", "semantic": "category", "display": "市"}, {"name": "report_time", "semantic": "category", "display": "上报时间"}]}', 'map', '["vehicle_realtime_position"]', '{"type": "realtime", "expected_delay_min": 5}', '{"max_rows": 500, "timeout_ms": 15000}', '{"cacheable": false}', '["vehicle.location.read"]', 'by_org', '["这台车现在在哪", "车辆最后位置"]', 'SELECT COALESCE(NULLIF(p.car_no, ''''), p.vin_code) AS plate_no,
       p.gcj02_lng AS lng,
       p.gcj02_lat AS lat,
       p.province_name,
       p.city_name,
       p.report_time
FROM vehicle_realtime_position p
WHERE (${vin_list_empty} OR p.vin_code IN (${vin_list}))
  AND p.vin_code IN (SELECT vin_code FROM basic_vehicle_info WHERE org_name IN (${acl_org_ids}))
ORDER BY p.report_time DESC
LIMIT 500
', 'online', 1, '数据组'),
('vehicle_online_status', 'capability', 'T1', '车辆在线状态', '查询车辆最近一次上报的在线状态', '["在线状态", "车辆状态", "是否在线", "上线情况"]', 'online', 1, '[{"name": "vehicle", "type": "string", "required": false, "description": "车牌号或 VIN"}, {"name": "vin_list", "type": "array<string>", "required": false, "max_items": 50}]', '{"shape": "table", "columns": [{"name": "plate_no", "semantic": "category", "display": "车牌号"}, {"name": "vehicle_status", "semantic": "category", "display": "车辆状态"}, {"name": "charge_status", "semantic": "category", "display": "充电状态"}, {"name": "speed", "semantic": "metric", "display": "速度", "unit": "km/h", "scale": 1}, {"name": "soc", "semantic": "metric", "display": "电量", "unit": "%"}, {"name": "dev_time", "semantic": "category", "display": "上报时间"}]}', 'table', '["tbox_realtime_vehicle_data", "basic_vehicle_info"]', '{"type": "realtime", "expected_delay_min": 5}', '{"max_rows": 500, "timeout_ms": 15000}', '{"cacheable": false}', '["vehicle.status.read"]', 'by_org', '["这台车在线吗", "车辆在线状态"]', 'SELECT COALESCE(b.car_no, t.vin_code) AS plate_no,
       CONCAT(''状态'', t.vehicle_status) AS vehicle_status,
       CONCAT(''状态'', t.charge_status) AS charge_status,
       t.speed,
       t.soc,
       t.dev_time
FROM tbox_realtime_vehicle_data t
LEFT JOIN basic_vehicle_info b ON t.vin_code = b.vin_code
WHERE (${vin_list_empty} OR t.vin_code IN (${vin_list}))
  AND t.vin_code IN (SELECT vin_code FROM basic_vehicle_info WHERE org_name IN (${acl_org_ids}))
ORDER BY t.dev_time DESC
LIMIT 500
', 'online', 1, '数据组'),
('vehicle_track', 'capability', 'T1', '车辆轨迹回放', '按时间范围回放车辆行驶轨迹（GPS 点序列）', '["轨迹", "轨迹回放", "行驶轨迹", "路线回放", "去过哪"]', 'location', 1, '[{"name": "time_range", "type": "daterange", "required": true, "max_span_days": 7, "description": "时间范围，如 昨天 / 近7天"}, {"name": "vehicle", "type": "string", "required": true, "description": "车牌号或 VIN"}, {"name": "vin_list", "type": "array<string>", "required": false, "max_items": 10}]', '{"shape": "table", "columns": [{"name": "dev_time", "semantic": "category", "display": "时间"}, {"name": "lng", "semantic": "geo_lng", "display": "经度"}, {"name": "lat", "semantic": "geo_lat", "display": "纬度"}, {"name": "speed", "semantic": "metric", "display": "速度", "unit": "km/h", "scale": 1}, {"name": "mileage", "semantic": "metric", "display": "累计里程", "unit": "km", "scale": 1}]}', 'map', '["tbox_realtime_histroy_data"]', '{"type": "t_plus_0", "expected_delay_min": 60}', '{"max_rows": 2000, "timeout_ms": 20000, "max_span_days": 7}', '{"cacheable": false}', '["vehicle.location.read"]', 'by_org', '["这台车的行驶轨迹", "轨迹回放看看"]', 'SELECT t.dev_time,
       ROUND(t.longitude, 6) AS lng,
       ROUND(t.latitude, 6) AS lat,
       t.speed,
       t.mileage
FROM tbox_realtime_histroy_data t
WHERE t.dev_time >= ${time_from}
  AND t.dev_time < ${time_to}
  AND t.vin_code IN (${vin_list})
  AND t.vin_code IN (SELECT vin_code FROM basic_vehicle_info WHERE org_name IN (${acl_org_ids}))
  AND t.longitude > 1 AND t.latitude > 1
ORDER BY t.dev_time
LIMIT 2000
', 'online', 1, '数据组'),
('vehicle_working_status', 'capability', 'T1', '车辆实时工况', '查询车辆当前工况：电量/速度/状态/能耗/电压电流', '["工况", "实时工况", "车辆工况", "电量多少", "能耗"]', 'online', 1, '[{"name": "vehicle", "type": "string", "required": true, "description": "车牌号或 VIN"}, {"name": "vin_list", "type": "array<string>", "required": false, "max_items": 50}]', '{"shape": "table", "columns": [{"name": "plate_no", "semantic": "category", "display": "车牌号"}, {"name": "soc", "semantic": "metric", "display": "电量", "unit": "%"}, {"name": "speed", "semantic": "metric", "display": "速度", "unit": "km/h", "scale": 1}, {"name": "vehicle_status", "semantic": "category", "display": "车辆状态"}, {"name": "charge_status", "semantic": "category", "display": "充电状态"}, {"name": "energy_consumption", "semantic": "metric", "display": "累计能耗", "unit": "kWh", "scale": 1}, {"name": "dev_time", "semantic": "category", "display": "上报时间"}]}', 'metric_card', '["vehicle_status_realtime_data"]', '{"type": "realtime", "expected_delay_min": 5}', '{"max_rows": 50, "timeout_ms": 10000}', '{"cacheable": false}', '["vehicle.status.read"]', 'by_org', '["这台车当前工况怎么样", "还有多少电"]', 'SELECT COALESCE(b.car_no, t.vin_code) AS plate_no,
       t.soc,
       t.speed,
       CONCAT(''状态'', t.vehicle_status) AS vehicle_status,
       CONCAT(''状态'', t.charge_status) AS charge_status,
       ROUND(t.energy_consumption, 1) AS energy_consumption,
       t.dev_time
FROM vehicle_status_realtime_data t
LEFT JOIN basic_vehicle_info b ON t.vin_code = b.vin_code
WHERE t.vin_code IN (${vin_list})
  AND t.vin_code IN (SELECT vin_code FROM basic_vehicle_info WHERE org_name IN (${acl_org_ids}))
LIMIT 50
', 'online', 1, '数据组');

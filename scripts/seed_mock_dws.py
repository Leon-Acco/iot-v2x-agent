"""
模拟数仓种子脚本（P0 打样）
在 MySQL 上建 v2x_dws_mock 库，模拟 Doris 的 DWS/DWD 表结构与数据，
等真实 Doris 连接信息到位后改 SQL 模板映射即可。

用法：python scripts/seed_mock_dws.py
"""
import random
import datetime as dt

import pymysql

random.seed(42)  # 确定性种子，保证可复现

CONN = dict(host="172.16.8.225", port=30316, user="test-admin",
            password="i1q7k7lmQZ", charset="utf8mb4", autocommit=True)

FLEETS = [("F001", "华东一车队"), ("F002", "华北二车队"), ("F003", "华南三车队")]
# 车队基准坐标（上海 / 北京 / 广州）
FLEET_GEO = {"F001": (121.47, 31.23, "上海市"), "F002": (116.40, 39.90, "北京市"), "F003": (113.26, 23.13, "广州市")}
PLATE_PREFIX = {"F001": "沪A", "F002": "京B", "F003": "粤C"}
ALARM_TYPES = ["AEB", "LDW", "FCW", "GPS漂移", "超速", "疲劳驾驶", "急加速", "急减速"]
ALARM_WEIGHTS = [8, 15, 12, 10, 25, 10, 12, 8]  # 超速最多，AEB 最少但有尖峰车


def vehicles():
    """生成 3 车队 × 10 台车"""
    result = []
    for fleet_id, fleet_name in FLEETS:
        for i in range(10):
            idx = FLEETS.index((fleet_id, fleet_name)) * 10 + i + 1
            vin = f"LTESTV2X{idx:08d}X"[:17].ljust(17, "0")
            plate = f"{PLATE_PREFIX[fleet_id]}{10001 + idx}"
            result.append({"vin": vin, "plate": plate, "fleet_id": fleet_id, "fleet_name": fleet_name})
    return result


def main():
    conn = pymysql.connect(**CONN)
    cur = conn.cursor()
    cur.execute("CREATE DATABASE IF NOT EXISTS v2x_dws_mock DEFAULT CHARSET utf8mb4")
    cur.execute("USE v2x_dws_mock")

    # ── 建表（对齐 Doris 表命名，切换真实 Doris 时只改连接）──
    cur.execute("""
        CREATE TABLE IF NOT EXISTS dws_vehicle_status_latest (
            vin VARCHAR(32), plate_no VARCHAR(16), fleet_id VARCHAR(16), fleet_name VARCHAR(64),
            online_status TINYINT, soc INT, last_seen_at DATETIME, updated_at DATETIME,
            PRIMARY KEY (vin), KEY idx_fleet (fleet_id)
        ) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4""")
    cur.execute("""
        CREATE TABLE IF NOT EXISTS dws_vehicle_location_latest (
            vin VARCHAR(32), plate_no VARCHAR(16), fleet_id VARCHAR(16),
            lng DECIMAL(10,6), lat DECIMAL(10,6), address VARCHAR(255), located_at DATETIME,
            PRIMARY KEY (vin), KEY idx_fleet (fleet_id)
        ) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4""")
    cur.execute("""
        CREATE TABLE IF NOT EXISTS dws_vehicle_mileage_di (
            stat_date DATE, vin VARCHAR(32), plate_no VARCHAR(16), fleet_id VARCHAR(16),
            fleet_name VARCHAR(64), mileage_km DECIMAL(8,1),
            PRIMARY KEY (stat_date, vin), KEY idx_fleet_date (fleet_id, stat_date)
        ) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4""")
    cur.execute("""
        CREATE TABLE IF NOT EXISTS dws_vehicle_alarm_di (
            stat_date DATE, alarm_time DATETIME, vin VARCHAR(32), plate_no VARCHAR(16),
            fleet_id VARCHAR(16), fleet_name VARCHAR(64), alarm_type VARCHAR(32), alarm_level VARCHAR(8),
            KEY idx_date (stat_date), KEY idx_fleet_date (fleet_id, stat_date), KEY idx_vin (vin)
        ) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4""")
    cur.execute("""
        CREATE TABLE IF NOT EXISTS dwd_vehicle_gps_hi (
            vin VARCHAR(32), fleet_id VARCHAR(16), lng DECIMAL(10,6), lat DECIMAL(10,6),
            speed DECIMAL(5,1), recorded_at DATETIME,
            KEY idx_vin_time (vin, recorded_at), KEY idx_fleet (fleet_id)
        ) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4""")

    for t in ["dws_vehicle_status_latest", "dws_vehicle_location_latest",
              "dws_vehicle_mileage_di", "dws_vehicle_alarm_di", "dwd_vehicle_gps_hi"]:
        cur.execute(f"TRUNCATE TABLE {t}")

    vs = vehicles()
    now = dt.datetime.now().replace(microsecond=0)
    today = now.date()

    # ── 状态表：约 80% 在线，离线车辆最后上线时间错落（含 >24h）──
    for v in vs:
        online = random.random() < 0.8
        if online:
            last_seen = now - dt.timedelta(minutes=random.randint(1, 30))
        else:
            last_seen = now - dt.timedelta(hours=random.choice([2, 5, 10, 26, 30, 50, 72]))
        cur.execute(
            "INSERT INTO dws_vehicle_status_latest VALUES (%s,%s,%s,%s,%s,%s,%s,%s)",
            (v["vin"], v["plate"], v["fleet_id"], v["fleet_name"],
             1 if online else 0, random.randint(15, 98), last_seen, now))

    # ── 位置表：车队基准坐标附近漂移 ──
    for v in vs:
        base_lng, base_lat, city = FLEET_GEO[v["fleet_id"]]
        lng = base_lng + random.uniform(-0.3, 0.3)
        lat = base_lat + random.uniform(-0.2, 0.2)
        cur.execute(
            "INSERT INTO dws_vehicle_location_latest VALUES (%s,%s,%s,%s,%s,%s,%s)",
            (v["vin"], v["plate"], v["fleet_id"], round(lng, 6), round(lat, 6),
             f"{city}某道路 {random.randint(1, 200)} 号附近",
             now - dt.timedelta(minutes=random.randint(1, 120))))

    # ── 里程表：近 30 天（含今天，今天为当日累计）× 30 车，周末略低 ──
    mileage_rows = []
    for d in range(30, -1, -1):
        day = today - dt.timedelta(days=d)
        weekend_factor = 0.6 if day.weekday() >= 5 else 1.0
        for v in vs:
            km = round(random.uniform(60, 320) * weekend_factor, 1)
            if d == 0:
                km = round(km * 0.4, 1)  # 今天：当日累计（非完整自然日）
            mileage_rows.append((day, v["vin"], v["plate"], v["fleet_id"], v["fleet_name"], km))
    cur.executemany("INSERT INTO dws_vehicle_mileage_di VALUES (%s,%s,%s,%s,%s,%s)", mileage_rows)

    # ── 告警表：近 30 天（含今天），加权随机；车 1（沪A10002）AEB 尖峰（异常分析演示用）──
    alarm_rows = []
    hot_vehicle = vs[0]
    for d in range(30, -1, -1):
        day = today - dt.timedelta(days=d)
        for v in vs:
            n = random.choices([0, 1, 2, 3, 5], weights=[40, 30, 15, 10, 5])[0]
            if d == 0:
                n = min(n, 1)  # 今天：当日累计
            for _ in range(n):
                alarm_type = random.choices(ALARM_TYPES, weights=ALARM_WEIGHTS)[0]
                t = dt.datetime.combine(day, dt.time(random.randint(0, 23), random.randint(0, 59)))
                if d == 0 and t > now:
                    t = now - dt.timedelta(minutes=random.randint(1, 60))
                alarm_rows.append((day, t, v["vin"], v["plate"], v["fleet_id"], v["fleet_name"],
                                   alarm_type, random.choice(["低", "中", "高"])))
        # 尖峰车：每天 3~6 次 AEB
        for _ in range(random.randint(3, 6)):
            t = dt.datetime.combine(day, dt.time(random.randint(6, 22), random.randint(0, 59)))
            if d == 0 and t > now:
                t = now - dt.timedelta(minutes=random.randint(1, 30))
            alarm_rows.append((day, t, hot_vehicle["vin"], hot_vehicle["plate"],
                               hot_vehicle["fleet_id"], hot_vehicle["fleet_name"], "AEB", "高"))
    cur.executemany("INSERT INTO dws_vehicle_alarm_di VALUES (%s,%s,%s,%s,%s,%s,%s,%s)", alarm_rows)

    # ── GPS 轨迹：尖峰车昨天一条 8 小时轨迹（5 分钟一个点）──
    gps_rows = []
    base_lng, base_lat, _ = FLEET_GEO[hot_vehicle["fleet_id"]]
    lng, lat = base_lng, base_lat
    for i in range(96):  # 8h × 12 点/h
        t = dt.datetime.combine(today - dt.timedelta(days=1), dt.time(8, 0)) + dt.timedelta(minutes=5 * i)
        lng += random.uniform(-0.01, 0.012)
        lat += random.uniform(-0.008, 0.008)
        gps_rows.append((hot_vehicle["vin"], hot_vehicle["fleet_id"],
                         round(lng, 6), round(lat, 6), round(random.uniform(0, 80), 1), t))
    cur.executemany("INSERT INTO dwd_vehicle_gps_hi VALUES (%s,%s,%s,%s,%s,%s)", gps_rows)

    print(f"种子完成: 车辆 {len(vs)}, 里程 {len(mileage_rows)}, 告警 {len(alarm_rows)}, GPS {len(gps_rows)}")
    cur.close()
    conn.close()


if __name__ == "__main__":
    main()

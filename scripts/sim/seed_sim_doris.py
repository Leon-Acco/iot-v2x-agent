
# -*- coding: utf-8 -*-
# dst_v2x_sim deterministic seed (idempotent: drop + create + insert).
# 3 fleets x 120 vehicles x 90 days, end 2026-09-02.
# Storylines: 5 battery-problem vehicles in F001 (declining mileage),
# F002 fatigue-alarm x3, 9 long-offline vehicles.
import math
import os
import random
import datetime as dt
import pymysql

HOST = os.environ.get('DORIS_HOST', '172.16.8.237')
PORT = int(os.environ.get('DORIS_PORT', '9030'))
USER = os.environ.get('DORIS_USER', 'root')
PASSWORD = os.environ.get('DORIS_PASSWORD', '827yzbW9UX1tLikd')
DB = 'dst_v2x_sim'
START_DATE = dt.date(2026, 6, 5)
DAYS = 90
SEED = 42

SZ = '深圳市'
GZ = '广州市'
BJ = '北京市'
GD = '广东省'
HN = '华南'
HB = '华北'
F1N = '深圳地上铁绿色配送'
F2N = '广州新创绿能物流'
F3N = '北京京运绿色城配'
BYD = '比亚迪'
YC = '远程'
FT = '福田'
DF = '东风'
KR = '开瑞'
TRUCK = '轻卡'
VAN = '微面'
FAST = '快充'
SLOW = '慢充'

FLEETS = [
    dict(id='F001', name=F1N, region=HN, province=GD, city=SZ, count=60,
         plate='粤B', center=(114.0579, 22.5431),
         districts=['南山区', '宝安区', '龙岗区', '龙华区', '福田区'],
         neighbors=['东莞市', '惠州市']),
    dict(id='F002', name=F2N, region=HN, province=GD, city=GZ, count=35,
         plate='粤A', center=(113.2644, 23.1291),
         districts=['黄埔区', '白云区', '番禺区', '天河区'],
         neighbors=['佛山市', '东莞市']),
    dict(id='F003', name=F3N, region=HB, province=BJ, city=BJ, count=25,
         plate='京A', center=(116.4074, 39.9042),
         districts=['大兴区', '通州区', '顺义区', '朝阳区'],
         neighbors=['廊坊市']),
]

MODELS = [
    (BYD, 'T5', TRUCK, 85.0, 250),
    (YC, 'V6E', VAN, 42.0, 260),
    (FT, 'ZL', TRUCK, 100.0, 300),
    (DF, 'KPT-EV', TRUCK, 90.0, 280),
    (KR, 'HT-EV', VAN, 45.0, 250),
]

ALARM_TYPES = [
    (1001, '前向碰撞报警', 3),
    (1002, '车道偏离报警', 2),
    (1003, '车距过近报警', 2),
    (1005, '超速报警', 2),
    (1006, '疲劳驾驶报警', 3),
    (1009, '分神驾驶报警', 2),
    (9001, '低电量报警', 1),
    (9002, '绝缘异常报警', 3),
    (9003, '电池高温报警', 3),
]
ALARM_INFO = {t[0]: (t[1], t[2]) for t in ALARM_TYPES}
ALARM_W_NORMAL = {1001: 10, 1002: 20, 1003: 25, 1005: 12, 1006: 6, 1009: 15, 9001: 7, 9002: 2, 9003: 3}
ALARM_W_F002 = dict(ALARM_W_NORMAL)
ALARM_W_F002[1006] = 22

FAULT_LIB = [
    ('P0AA6', '高压电池绝缘故障', '电池系统', 3),
    ('U0111', 'BMS通讯丢失', '电池系统', 3),
    ('P0C76', '电池包温差过大', '电池系统', 2),
    ('P0A80', '动力电池老化超标', '电池系统', 2),
    ('P0611', '电机控制器过温', '电机系统', 3),
    ('P0A2F', '驱动电机温度异常', '电机系统', 2),
    ('U0155', '仪表通讯中断', '车身电器', 1),
    ('C1234', '制动踏板信号异常', '制动系统', 3),
    ('P0562', '低压蓄电池电压过低', '车身电器', 1),
    ('P20E8', '充电接口温度异常', '充电系统', 2),
]
BATTERY_FAULTS = FAULT_LIB[0:3]

FENCES = {
    'F001': [('前海中心仓', 'warehouse'), ('盐田快充站', 'station'), ('南山限行区', 'restricted')],
    'F002': [('黄埔物流园', 'warehouse'), ('天河充电站', 'station'), ('珠江新城限行区', 'restricted')],
    'F003': [('亦庄配送中心', 'warehouse'), ('大兴充电站', 'station'), ('通州限行区', 'restricted')],
}
STATIONS = {
    'F001': ['盐田快充站', '宝安超充站', '龙华物流园充电站'],
    'F002': ['天河充电站', '黄埔物流园充电站', '番禺超充站'],
    'F003': ['大兴充电站', '亦庄超充站', '通州物流园充电站'],
}

PROBLEM_SEQS = set(range(0, 5))
HERO_PLATE = '粤BD96880'


def build_vehicles(rnd):
    vehicles = []
    offline_story = {}
    seq = 0
    for fl in FLEETS:
        for i in range(fl['count']):
            brand, model, vtype, cap, rng = MODELS[rnd.randrange(len(MODELS))]
            vin = 'LSIM%013d' % seq
            plate = '%sD%05d' % (fl['plate'], 10000 + i)
            if fl['id'] == 'F001' and i == 0:
                plate = HERO_PLATE
            vehicles.append(dict(
                seq=seq, vin=vin, plate=plate, fleet=fl['id'], brand=brand, model=model,
                vtype=vtype, cap=cap, rng=rng, year=rnd.choice([2021, 2022, 2023, 2024]),
                odo=rnd.uniform(5000, 60000), city=fl['city'], province=fl['province'],
                center=fl['center'], districts=fl['districts'], neighbors=fl['neighbors'],
                status=1))
            seq += 1
    story = [
        ('F001', 55, 9), ('F001', 56, 9), ('F001', 57, 15), ('F001', 58, 15), ('F001', 59, 25),
        ('F002', 33, 3), ('F002', 34, 16),
        ('F003', 23, 4), ('F003', 24, 28),
    ]
    base_seq = {'F001': 0, 'F002': 60, 'F003': 95}
    end_date = START_DATE + dt.timedelta(days=DAYS - 1)
    for fid, idx, days_ago in story:
        gseq = base_seq[fid] + idx
        offline_story[gseq] = end_date - dt.timedelta(days=days_ago)
        if days_ago >= 20:
            vehicles[gseq]['status'] = 2
    return vehicles, offline_story


def jitter(center, rnd, scale=0.25):
    return (round(center[0] + rnd.uniform(-scale, scale), 6),
            round(center[1] + rnd.uniform(-scale, scale), 6))


def poisson(rnd, lam):
    limit = math.exp(-lam)
    k = 0
    p = 1.0
    while p > limit:
        k += 1
        p *= rnd.random()
    return k - 1


def simulate(rnd, vehicles, offline_story):
    rows = dict(fleet=[], vehicle=[], status=[], mileage=[], trip=[], charge=[], alarm=[], fault=[], fence=[])
    for fl in FLEETS:
        rows['fleet'].append((fl['id'], fl['name'], fl['region'], fl['province'], fl['city'], fl['count'], 'sim seed demo'))
    for v in vehicles:
        rows['vehicle'].append((v['vin'], v['plate'], v['fleet'], v['brand'], v['model'], 'BEV',
                                v['vtype'], v['year'], v['rng'], v['cap'],
                                dt.date(v['year'], 1, 15), v['status']))
    tid = 0
    cid = 0
    aid = 0
    fid = 0
    eid = 0
    alarm_types = [t[0] for t in ALARM_TYPES]
    for v in vehicles:
        off = offline_story.get(v['seq'])
        problem = v['seq'] in PROBLEM_SEQS
        wmap = ALARM_W_F002 if v['fleet'] == 'F002' else ALARM_W_NORMAL
        weights = [wmap[t] for t in alarm_types]
        for day_i in range(DAYS):
            day = START_DATE + dt.timedelta(days=day_i)
            if off and day > off:
                continue
            if rnd.random() < 0.06:
                continue
            factor = 1.0
            if problem:
                factor = max(0.35, 1.0 - 0.75 * day_i / DAYS)
            base = dt.datetime.combine(day, dt.time(0, 0))
            n_trip = rnd.randint(2, 6)
            t_min = rnd.randint(360, 480)
            day_mileage = 0.0
            day_drive = 0
            max_sp = 0.0
            first_start = None
            last_end = None
            for k in range(n_trip):
                dur = rnd.randint(25, 90)
                speed = rnd.uniform(22, 58)
                km = round(dur * speed / 60.0 * factor, 2)
                if km < 2.0:
                    km = 2.0
                start_t = base + dt.timedelta(minutes=t_min)
                end_t = start_t + dt.timedelta(minutes=dur)
                slng, slat = jitter(v['center'], rnd)
                elng, elat = jitter(v['center'], rnd)
                end_city = v['city'] if rnd.random() > 0.08 else rnd.choice(v['neighbors'])
                tid += 1
                rows['trip'].append(('T%010d' % tid, v['vin'], start_t, end_t, slng, slat,
                                     elng, elat, v['city'], end_city, km, dur, round(speed, 1)))
                day_mileage += km
                day_drive += dur
                max_sp = max(max_sp, speed + rnd.uniform(0, 25))
                if first_start is None:
                    first_start = start_t
                last_end = end_t
                t_min = t_min + dur + rnd.randint(30, 180)
                if t_min > 1320:
                    break
            night = rnd.randint(0, 45) if rnd.random() < 0.25 else 0
            avg_sp = round(day_mileage / max(day_drive, 1) * 60, 1) if day_drive else 0.0
            rows['mileage'].append((day, v['vin'], round(day_mileage, 2), day_drive, avg_sp,
                                    round(max_sp, 1), night))
            v['odo'] += day_mileage
            first_on = base + dt.timedelta(minutes=rnd.randint(330, 420))
            last_on = base + dt.timedelta(minutes=min(t_min + rnd.randint(10, 60), 1439))
            olng, olat = jitter(v['center'], rnd)
            online_min = max(int((last_on - first_on).total_seconds() // 60), 0)
            rows['status'].append((day, v['vin'], min(online_min, 1440), first_on, last_on,
                                   olng, olat, v['province'], v['city'],
                                   rnd.choice(v['districts']), round(v['odo'], 1)))
            if rnd.random() < 0.85:
                n_chg = 2 if rnd.random() < 0.2 else 1
                for c in range(n_chg):
                    soc0 = rnd.randint(15, 45)
                    soc1 = rnd.randint(80, 98)
                    fast = rnd.random() < 0.7
                    kwh = round(v['cap'] * (soc1 - soc0) / 100.0 * rnd.uniform(0.95, 1.05), 1)
                    power = rnd.uniform(50, 90) if fast else rnd.uniform(6, 10)
                    durc = max(int(kwh / power * 60), 10)
                    cs = base + dt.timedelta(minutes=rnd.randint(660, 1260))
                    cid += 1
                    rows['charge'].append(('C%010d' % cid, v['vin'], cs, cs + dt.timedelta(minutes=durc),
                                           FAST if fast else SLOW, kwh, soc0, soc1, durc,
                                           rnd.choice(STATIONS[v['fleet']]), v['city']))

            lam = 1.5 if v['fleet'] == 'F002' else 1.2
            for a in range(poisson(rnd, lam)):
                at = rnd.choices(alarm_types, weights=weights)[0]
                aname, alevel = ALARM_INFO[at]
                ats = base + dt.timedelta(minutes=rnd.randint(360, 1320))
                alng, alat = jitter(v['center'], rnd)
                aid += 1
                rows['alarm'].append(('A%010d' % aid, v['vin'], at, aname, alevel, ats,
                                      ats + dt.timedelta(seconds=rnd.randint(5, 120)),
                                      alng, alat, v['city'], round(rnd.uniform(20, 90), 1)))
            if problem:
                for a in range(rnd.randint(2, 5)):
                    at = rnd.choice([9001, 9002, 9003])
                    aname, alevel = ALARM_INFO[at]
                    ats = base + dt.timedelta(minutes=rnd.randint(360, 1320))
                    alng, alat = jitter(v['center'], rnd)
                    aid += 1
                    rows['alarm'].append(('A%010d' % aid, v['vin'], at, aname, alevel, ats,
                                          ats + dt.timedelta(seconds=rnd.randint(5, 180)),
                                          alng, alat, v['city'], round(rnd.uniform(0, 60), 1)))
            if rnd.random() < 0.05:
                fc = rnd.choice(FAULT_LIB)
                ft = base + dt.timedelta(minutes=rnd.randint(400, 1300))
                rec = None if rnd.random() < 0.15 else ft + dt.timedelta(hours=rnd.randint(1, 48))
                fid += 1
                rows['fault'].append(('F%010d' % fid, v['vin'], fc[0], fc[1], fc[2], fc[3],
                                      ft, rec, v['city']))
            if problem:
                for f in range(rnd.randint(1, 2)):
                    fc = rnd.choice(BATTERY_FAULTS)
                    ft = base + dt.timedelta(minutes=rnd.randint(400, 1300))
                    rec = None if rnd.random() < 0.3 else ft + dt.timedelta(hours=rnd.randint(2, 72))
                    fid += 1
                    rows['fault'].append(('F%010d' % fid, v['vin'], fc[0], fc[1], fc[2], fc[3],
                                          ft, rec, v['city']))
            if rnd.random() < 0.5:
                fname, ftype = rnd.choice(FENCES[v['fleet']])
                et = base + dt.timedelta(minutes=rnd.randint(420, 1200))
                glng, glat = jitter(v['center'], rnd, 0.08)
                eid += 1
                rows['fence'].append(('E%010d' % eid, v['vin'], fname, ftype, 'enter', et,
                                      glng, glat, v['city']))
                eid += 1
                rows['fence'].append(('E%010d' % eid, v['vin'], fname, ftype, 'exit',
                                      et + dt.timedelta(minutes=rnd.randint(20, 180)),
                                      glng, glat, v['city']))
    return rows


INSERTS = {
    'fleet': 'INSERT INTO dim_fleet (fleet_id,fleet_name,region,province,city,vehicle_count,remark) VALUES (%s,%s,%s,%s,%s,%s,%s)',
    'vehicle': 'INSERT INTO dim_vehicle (vin,plate_no,fleet_id,brand,model,energy_type,vehicle_type,manufacture_year,rated_range_km,battery_capacity_kwh,register_date,status) VALUES (%s,%s,%s,%s,%s,%s,%s,%s,%s,%s,%s,%s)',
    'status': 'INSERT INTO fact_vehicle_status_daily (dt,vin,online_minutes,first_online_time,last_online_time,last_lng,last_lat,province,city,district,odometer_km) VALUES (%s,%s,%s,%s,%s,%s,%s,%s,%s,%s,%s)',
    'mileage': 'INSERT INTO fact_mileage_daily (dt,vin,mileage_km,driving_minutes,avg_speed,max_speed,night_driving_minutes) VALUES (%s,%s,%s,%s,%s,%s,%s)',
    'trip': 'INSERT INTO fact_trip (trip_id,vin,start_time,end_time,start_lng,start_lat,end_lng,end_lat,start_city,end_city,mileage_km,duration_min,avg_speed) VALUES (%s,%s,%s,%s,%s,%s,%s,%s,%s,%s,%s,%s,%s)',
    'charge': 'INSERT INTO fact_charge (charge_id,vin,start_time,end_time,charge_type,charge_kwh,start_soc,end_soc,duration_min,station_name,city) VALUES (%s,%s,%s,%s,%s,%s,%s,%s,%s,%s,%s)',
    'alarm': 'INSERT INTO fact_alarm (alarm_id,vin,alarm_type,alarm_name,alarm_level,start_time,end_time,lng,lat,city,speed_kmh) VALUES (%s,%s,%s,%s,%s,%s,%s,%s,%s,%s,%s)',
    'fault': 'INSERT INTO fact_fault (fault_id,vin,fault_code,fault_name,fault_part,fault_level,report_time,recover_time,city) VALUES (%s,%s,%s,%s,%s,%s,%s,%s,%s)',
    'fence': 'INSERT INTO fact_geofence_event (event_id,vin,fence_name,fence_type,event_type,event_time,lng,lat,city) VALUES (%s,%s,%s,%s,%s,%s,%s,%s,%s)',
}


def apply_ddl(cur):
    ddl_path = os.path.join(os.path.dirname(os.path.abspath(__file__)), 'ddl.sql')
    with open(ddl_path, encoding='utf-8') as f:
        text = f.read()
    for stmt in text.split(';'):
        stmt = stmt.strip()
        if not stmt:
            continue
        lines = [ln for ln in stmt.split(chr(10)) if not ln.strip().startswith('--')]
        stmt = chr(10).join(lines).strip()
        if stmt:
            cur.execute(stmt)


def flush(cur, table, data):
    sql = INSERTS[table]
    total = 0
    for i in range(0, len(data), 2000):
        cur.executemany(sql, data[i:i + 2000])
        total += len(data[i:i + 2000])
    return total


def main():
    rnd = random.Random(SEED)
    vehicles, offline_story = build_vehicles(rnd)
    rows = simulate(rnd, vehicles, offline_story)
    conn = pymysql.connect(host=HOST, port=PORT, user=USER, password=PASSWORD, charset='utf8mb4')
    try:
        cur = conn.cursor()
        apply_ddl(cur)
        conn.commit()
        cur.execute('USE ' + DB)
        for table in ['fleet', 'vehicle', 'status', 'mileage', 'trip', 'charge', 'alarm', 'fault', 'fence']:
            n = flush(cur, table, rows[table])
            conn.commit()
            print('%-8s %8d rows' % (table, n))
        cur.execute('SELECT COUNT(1) FROM fact_alarm')
        print('alarm check:', cur.fetchone()[0])
    finally:
        conn.close()
    print('SEED DONE')


if __name__ == '__main__':
    main()

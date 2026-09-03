"""P0 验收回归：30 条样例问题 → capability 选择准确率（目标 ≥85%）"""
import json
import uuid
import os
import urllib.request
import http.cookiejar

BASE = os.environ.get("V2X_BASE", "http://localhost:8080")

# (问题, 期望 capability_id, None=期望拒答/澄清)
CASES = [
    # L1 单点查询
    ("粤M32758现在在线吗", "vehicle_online_status"),
    ("粤M32793这台车状态怎么样", "vehicle_online_status"),
    ("粤M32758现在在哪", "vehicle_last_location"),
    ("粤M32793的最后位置", "vehicle_last_location"),
    ("粤M32758的电量还有多少", "vehicle_online_status"),
    # L2 清单查询
    ("离线超24小时的车有哪些", "offline_vehicle_list"),
    ("近3天触发AEB的车辆清单", "alarm_list"),
    ("这台车昨天有哪些告警", "alarm_list"),
    ("上周超速告警明细", "alarm_list"),
    ("近7天的告警列表", "alarm_list"),
    ("哪些车掉线了", "offline_vehicle_list"),
    ("失联车辆清单", "offline_vehicle_list"),
    # L3 聚合出图
    ("各车队近7天里程对比", "mileage_by_fleet"),
    ("近7天各类告警次数", "alarm_count_by_type"),
    ("上周哪个车队跑得最多", "mileage_by_fleet"),
    ("本周告警类型分布", "alarm_count_by_type"),
    ("近30天每日里程趋势", "mileage_daily"),
    ("昨天总里程", "mileage_daily"),
    ("哪类告警最多", "alarm_count_by_type"),
    ("车队里程排名", "mileage_by_fleet"),
    ("近7天每天跑多少公里", "mileage_daily"),
    # 轨迹
    ("粤M32758上周的行驶轨迹", "vehicle_track"),
    ("粤M32793上周的轨迹", "vehicle_track"),
    ("粤M32758近七天去过哪", "vehicle_track"),
    # 拒答（超能力范围）
    ("今天天气怎么样", None),
    ("帮我订一张机票", None),
    ("附近有什么好吃的", None),
    ("怎么修改车辆绑定的手机号", None),
    ("播放一首周杰伦的歌", None),
    ("帮我写一首诗", None),
]

def main():
    cj = http.cookiejar.CookieJar()
    opener = urllib.request.build_opener(urllib.request.HTTPCookieProcessor(cj))
    opener.open(urllib.request.Request(BASE + "/api/auth/login",
        data=json.dumps({"username": "admin", "password": "admin123"}).encode(),
        headers={"Content-Type": "application/json"}))

    passed = 0
    failed = []
    for i, (question, expected) in enumerate(CASES):
        req = urllib.request.Request(BASE + "/agui/run",
            data=json.dumps({"threadId": f"t-eval-{i}", "runId": uuid.uuid4().hex, "messages": [{"id": uuid.uuid4().hex, "role": "user", "content": question}]}).encode(),
            headers={"Content-Type": "application/json"})
        actual = None
        refused = False
        clarified = False
        timed_out = False
        try:
            with opener.open(req, timeout=120) as resp:
                for raw in resp.read().decode("utf-8", "ignore").splitlines():
                    if raw.startswith("data:"):
                        frame = json.loads(raw[5:])
                        if frame.get("type") == "TOOL_CALL_START":
                            actual = frame.get("toolCallId")
                        elif frame.get("type") == "CUSTOM" and frame.get("name") == "REFUSE":
                            refused = True
                        elif frame.get("type") == "CUSTOM" and frame.get("name") == "CLARIFY":
                            clarified = True
        except Exception:
            timed_out = True
        ok = ((actual == expected) if expected else (refused or clarified)) and not timed_out
        if ok:
            passed += 1
        else:
            failed.append((question, expected, "TIMEOUT" if timed_out else (actual or ("REFUSE" if refused else "CLARIFY"))))
        print(f"{'PASS' if ok else 'FAIL'}  {question}  ->  {'TIMEOUT' if timed_out else (actual or ('REFUSE' if refused else 'CLARIFY'))}（期望 {expected or '拒答/澄清'}）")

    print(f"\n===== 准确率: {passed}/{len(CASES)} = {passed/len(CASES)*100:.0f}%（P0 目标 ≥85%）=====")
    if failed:
        print("失败用例:")
        for q, e, a in failed:
            print(f"  {q}: 期望 {e}, 实际 {a}")

if __name__ == "__main__":
    main()

"""端到端冒烟测试：登录 → 多个问题跑 /agui/run SSE 全链路 → 汇总事件序列（官方 AG-UI 契约）"""
import json
import uuid
import urllib.request
import http.cookiejar

BASE = "http://localhost:8080"

CASES = [
    ("q-mileage-fleet", "各车队近7天里程对比"),
    ("q-alarm-stat", "近7天各类告警次数"),
    ("q-online", "粤M32758现在在线吗"),
    ("q-offline", "离线超24小时的车有哪些"),
    ("q-track", "粤M32758上周的行驶轨迹"),
    ("q-anomaly", "粤M32545为什么频繁告警"),
    ("q-followup", "那前天呢"),
    ("q-out", "今天天气怎么样"),
]

def main():
    cj = http.cookiejar.CookieJar()
    opener = urllib.request.build_opener(urllib.request.HTTPCookieProcessor(cj))
    login = urllib.request.Request(BASE + "/api/auth/login",
        data=json.dumps({"username": "admin", "password": "admin123"}).encode(),
        headers={"Content-Type": "application/json"})
    print("login:", json.loads(opener.open(login).read()))

    thread = "t-smoke"
    for name, question in CASES:
        body = {
            "threadId": thread,
            "runId": uuid.uuid4().hex,
            "messages": [{"id": uuid.uuid4().hex, "role": "user", "content": question}],
        }
        req = urllib.request.Request(BASE + "/agui/run",
            data=json.dumps(body).encode(),
            headers={"Content-Type": "application/json"})
        events = []
        cap = None
        try:
            with opener.open(req, timeout=30) as resp:
                for raw in resp.read().decode("utf-8", "ignore").splitlines():
                    if raw.startswith("data:"):
                        frame = json.loads(raw[5:])
                        t = frame.get("type")
                        if t == "CUSTOM":
                            t = "CUSTOM:" + str(frame.get("name"))
                        events.append(t)
                        if frame.get("type") == "TOOL_CALL_START":
                            cap = frame.get("toolCallName")
        except Exception as e:
            events.append("HTTP_ERROR:" + str(e))
        seq = " → ".join(dict.fromkeys(events))
        print(f"[{name}] {question}")
        print(f"  capability: {cap}")
        print(f"  events: {seq}")

if __name__ == "__main__":
    main()

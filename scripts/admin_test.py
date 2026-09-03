"""管理后台 API 冒烟：capability 列表 / 试跑 / 上下线 / 反馈"""
import json
import os
import urllib.request
import http.cookiejar

BASE = os.environ.get("V2X_BASE", "http://localhost:8080")

def main():
    cj = http.cookiejar.CookieJar()
    opener = urllib.request.build_opener(urllib.request.HTTPCookieProcessor(cj))
    opener.open(urllib.request.Request(BASE + "/api/auth/login",
        data=json.dumps({"username": "admin", "password": "admin123"}).encode(),
        headers={"Content-Type": "application/json"}))

    def call(path, body=None, method=None):
        data = json.dumps(body, ensure_ascii=False).encode() if body is not None else None
        req = urllib.request.Request(BASE + path, data=data,
            headers={"Content-Type": "application/json"}, method=method or ("POST" if data else "GET"))
        return json.loads(opener.open(req, timeout=30).read())

    caps = call("/admin/capabilities")
    print(f"capability 列表: {len(caps)} 个 -> {[c['id'] for c in caps]}")

    r = call("/admin/capabilities/mileage_by_fleet/dry-run", {"params": {"time_range": "近7天"}}, "POST")
    print(f"dry-run: rows={r['rowCount']} elapsed={r['elapsedMs']}ms")
    print(f"  sql: {r['sqlSnapshot'][:200]}")

    r = call("/admin/capabilities/vehicle_track/status", {"status": "deprecated"}, "POST")
    print(f"下线 vehicle_track: {r}")
    r = call("/admin/capabilities/vehicle_track/status", {"status": "online"}, "POST")
    print(f"恢复上线: {r}")

    # 反馈入库 + 列表
    cj2 = http.cookiejar.CookieJar()
    op = urllib.request.build_opener(urllib.request.HTTPCookieProcessor(cj2))
    op.open(urllib.request.Request(BASE + "/api/auth/login",
        data=json.dumps({"username": "op1", "password": "op123456"}).encode(),
        headers={"Content-Type": "application/json"}))
    fb = urllib.request.Request(BASE + "/ag-ui/feedback",
        data=json.dumps({"runId": "t-smoke-fake", "traceId": "trace-x", "rating": 1, "comment": "结果准确"}).encode(),
        headers={"Content-Type": "application/json"})
    print("feedback:", json.loads(op.open(fb).read()))
    rows = call("/admin/feedback")
    print(f"反馈列表: {len(rows)} 条, 最新 rating={rows[0]['rating']}")

if __name__ == "__main__":
    main()

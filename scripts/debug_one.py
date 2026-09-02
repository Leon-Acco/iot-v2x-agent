"""单问题调试：打印完整 SSE 事件"""
import json
import sys
import urllib.request
import http.cookiejar

BASE = "http://localhost:8080"

def main():
    question = sys.argv[1] if len(sys.argv) > 1 else "沪A10002昨天的行驶轨迹"
    thread = sys.argv[2] if len(sys.argv) > 2 else "t-debug"
    cj = http.cookiejar.CookieJar()
    opener = urllib.request.build_opener(urllib.request.HTTPCookieProcessor(cj))
    opener.open(urllib.request.Request(BASE + "/api/auth/login",
        data=json.dumps({"username": "admin", "password": "admin123"}).encode(),
        headers={"Content-Type": "application/json"}))
    req = urllib.request.Request(BASE + "/ag-ui/run",
        data=json.dumps({"threadId": thread, "question": question}).encode(),
        headers={"Content-Type": "application/json"})
    with opener.open(req, timeout=30) as resp:
        for raw in resp.read().decode("utf-8", "ignore").splitlines():
            if raw.startswith("data:"):
                frame = json.loads(raw[5:])
                t = frame["type"]
                if t in ("RUN_ERROR", "TOOL_CALL_ARGS", "TOOL_CALL_RESULT", "REFUSE", "CLARIFY"):
                    print(t, json.dumps(frame["payload"], ensure_ascii=False)[:800])
                elif t in ("TOOL_CALL_START",):
                    print(t, frame["payload"])

if __name__ == "__main__":
    main()

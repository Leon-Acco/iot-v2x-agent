# -*- coding: utf-8 -*-
"""运行时验证（fleet_copilot SSE 全链路）：
1) guide-alarm    —— load_capability_guide 说明书是否被专家调用（软规则：口语化问法诱导）
2) schema-tables  —— 昨晚遗留：SchemaTools.list_tables（CROSS 探索工具）
3) budget-cross   —— 预算动态注入：3+ 次取证后是否收敛（不再发起新 TOOL_CALL）
4) empty-backfill —— 昨晚遗留：空结果回填（无数据时段，模型应换范围或如实说明）
输出：路由 / 工具调用序列 / 结论摘要 / 错误帧
"""
import json
import os
import sys
import time
import uuid
import urllib.request
import http.cookiejar

sys.stdout.reconfigure(encoding="utf-8", errors="replace")
sys.stderr.reconfigure(encoding="utf-8", errors="replace")

BASE = "http://localhost:8080"
RESULT = os.path.join(os.path.dirname(os.path.abspath(__file__)), "verify_result.jsonl")

CASES = [
    ("guide-alarm", "最近一周哪些报警最频繁呀？帮我盘一盘各类型的量", 150),
    ("schema-tables", "分析库里都有哪些数据表？我想了解你们平台有什么数据", 150),
    ("budget-cross", "对比各车队近7天的告警情况和里程情况，给个综合结论", 200),
    ("empty-backfill", "2025年1月的告警统计是什么样", 180),
]


def run_case(opener, name, question, timeout_s):
    body = {
        "threadId": "t-verify-" + name,
        "runId": uuid.uuid4().hex,
        "messages": [{"id": uuid.uuid4().hex, "role": "user", "content": question}],
    }
    req = urllib.request.Request(
        BASE + "/agui/run/fleet_copilot",
        data=json.dumps(body).encode(),
        headers={"Content-Type": "application/json"})
    tool_calls = []
    route = ""
    text_parts = []
    errors = []
    custom_names = []
    t0 = time.time()
    try:
        with opener.open(req, timeout=timeout_s) as resp:
            for raw in resp:
                line = raw.decode("utf-8", "ignore").strip()
                if not line.startswith("data:"):
                    continue
                try:
                    f = json.loads(line[5:])
                except Exception:
                    continue
                t = f.get("type")
                if t == "TOOL_CALL_START":
                    tool_calls.append(f.get("toolCallName"))
                elif t == "CUSTOM":
                    n = f.get("name")
                    custom_names.append(n)
                    if n == "AGENT_ROUTE":
                        route = str(f.get("value", {}).get("route", "?"))
                elif t == "TEXT_MESSAGE_CONTENT":
                    delta = f.get("delta", "")
                    if delta:
                        text_parts.append(delta)
                elif t == "RUN_ERROR":
                    errors.append(json.dumps(f.get("message", f), ensure_ascii=False))
    except Exception as e:
        errors.append("HTTP:" + str(e))
    dur = round(time.time() - t0, 1)
    text = "".join(text_parts).replace("\n", " ")
    print("=" * 72)
    print(f"[{name}] {question}")
    print(f"  route={route}  dur={dur}s  tools({len(tool_calls)})={tool_calls}")
    print(f"  customs={sorted(set(custom_names))}")
    if errors:
        print(f"  ERRORS: {errors}")
    print(f"  conclusion[:260]: {text[:260]}")
    with open(RESULT, "a", encoding="utf-8") as f:
        f.write(json.dumps({"case": name, "question": question, "route": route,
                            "dur": dur, "tools": tool_calls, "customs": sorted(set(custom_names)),
                            "errors": errors, "conclusion": text[:600]}, ensure_ascii=False) + "\n")
    return tool_calls


def main():
    cj = http.cookiejar.CookieJar()
    opener = urllib.request.build_opener(urllib.request.HTTPCookieProcessor(cj))
    login = urllib.request.Request(
        BASE + "/api/auth/login",
        data=json.dumps({"username": "admin", "password": "admin123"}).encode(),
        headers={"Content-Type": "application/json"})
    print("login:", json.loads(opener.open(login, timeout=10).read()))
    for name, q, to in CASES:
        run_case(opener, name, q, to)


if __name__ == "__main__":
    main()

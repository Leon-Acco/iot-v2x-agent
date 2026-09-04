# -*- coding: utf-8 -*-
# 工作台流水账改造验证：路由思考帧 + 产物帧标注 + 会话恢复快照
import json, uuid, urllib.request, http.cookiejar, sys, time
sys.stdout.reconfigure(encoding='utf-8')

BASE = 'http://localhost:8080'

def login_opener():
    cj = http.cookiejar.CookieJar()
    opener = urllib.request.build_opener(urllib.request.HTTPCookieProcessor(cj))
    login = urllib.request.Request(BASE + '/api/auth/login',
        data=json.dumps({'username': 'admin', 'password': 'admin123'}).encode(),
        headers={'Content-Type': 'application/json'})
    opener.open(login).read()
    return opener

def run_q(opener, thread, question):
    body = {'threadId': thread, 'runId': uuid.uuid4().hex,
            'messages': [{'id': uuid.uuid4().hex, 'role': 'user', 'content': question}]}
    req = urllib.request.Request(BASE + '/agui/run',
        data=json.dumps(body).encode(), headers={'Content-Type': 'application/json'})
    frames = []
    with opener.open(req, timeout=120) as r:
        for raw in r.read().decode('utf-8', 'ignore').splitlines():
            if raw.startswith('data:'):
                frames.append(json.loads(raw[5:]))
    return frames

def main():
    opener = login_opener()
    print('login ok')
    frames = run_q(opener, 't-ledger', '近7天各类型告警次数')

    # 1. THINKING 帧含路由决策
    think = ''.join(f.get('value', {}).get('delta', '') for f in frames
                    if f.get('type') == 'CUSTOM' and f.get('name') == 'THINKING')
    print('--- THINKING ---')
    print(think[:400] if think else '(none)')
    ok1 = '路由决策' in think and 'alarm_count_by_type' in think

    # 2. TOOL_CALL_RESULT 带 capabilityDisplay
    result_frames = [f for f in frames if f.get('type') == 'TOOL_CALL_RESULT']
    ok2 = False
    if result_frames:
        # content 是 JSON 字符串
        try:
            content = json.loads(result_frames[0].get('content', '{}'))
        except Exception:
            content = {}
        print('--- TOOL_CALL_RESULT keys ---')
        print(sorted(content.keys()))
        ok2 = 'capabilityDisplay' in content

    # 3. CHART_SPEC 带标注
    chart_frames = [f for f in frames if f.get('type') == 'CUSTOM' and f.get('name') == 'CHART_SPEC']
    ok3 = False
    if chart_frames:
        v = chart_frames[0].get('value', {})
        print('--- CHART_SPEC keys ---')
        print(sorted(v.keys()))
        ok3 = 'capabilityDisplay' in v

    print()
    print('check1 thinking-route:', 'PASS' if ok1 else 'FAIL')
    print('check2 result-display:', 'PASS' if ok2 else 'FAIL')
    print('check3 chart-display:', 'PASS' if ok3 else 'FAIL')

    # 4. 会话恢复 payload_json（等落库）
    time.sleep(2)
    sess = json.loads(opener.open(BASE + '/ag-ui/sessions?profileId=fleet_copilot', timeout=10).read())
    target = None
    for s in sess:
        if s.get('threadId') == 't-ledger':
            target = s
    print('check4 session-found:', 'PASS' if target else 'FAIL')
    if target:
        msgs = json.loads(opener.open(
            BASE + '/ag-ui/sessions/' + str(target['sessionId']) + '/messages', timeout=10).read())
        for m in msgs:
            if m.get('role') == 'assistant' and m.get('payload'):
                p = m['payload']
                has_tools = isinstance(p.get('tools'), list) and len(p['tools']) > 0
                has_chart = p.get('chart') is not None
                print('check5 payload-snapshot:', 'PASS' if (has_tools or has_chart) else 'FAIL',
                      '| tools:', len(p.get('tools') or []), '| chart:', bool(has_chart))
                break

if __name__ == '__main__':
    main()

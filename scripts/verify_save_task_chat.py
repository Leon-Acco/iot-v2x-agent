# -*- coding: utf-8 -*-
"""save_task 内置工具链路验证：真实对话 SSE -> 模型查数据 + 调 save_task -> 定时任务落库
结果全文写入 /tmp/chat_result.json（UTF-8），控制台只输出 ASCII 状态行。
"""
import json
import time
import urllib.request

BASE = 'http://localhost:8080'
COOKIE = None

QUESTION = '粤C10003 近7天告警类型统计，查完把它存为每天早上8点自动执行的定时任务'


def login():
    global COOKIE
    req = urllib.request.Request(BASE + '/api/auth/login',
                                 data=json.dumps({'username': 'admin', 'password': 'admin123'}).encode(),
                                 headers={'Content-Type': 'application/json'}, method='POST')
    with urllib.request.urlopen(req, timeout=30) as resp:
        sc = resp.headers.get('Set-Cookie')
        assert sc and 'V2X_SESSION=' in sc, 'login failed'
        COOKIE = sc.split('V2X_SESSION=')[1].split(';')[0]


def chat():
    run_id = str(int(time.time() * 1000))
    body = {
        'threadId': 'verify-savetask-' + run_id,
        'runId': run_id,
        'messages': [{'id': run_id + '-u', 'role': 'user', 'content': QUESTION}]
    }
    req = urllib.request.Request(BASE + '/agui/run/fleet_copilot',
                                 data=json.dumps(body).encode('utf-8'),
                                 headers={'Content-Type': 'application/json',
                                          'Cookie': 'V2X_SESSION=' + COOKIE},
                                 method='POST')
    events = []
    cur = []
    with urllib.request.urlopen(req, timeout=300) as resp:
        for raw in resp:
            line = raw.decode('utf-8').rstrip('\r\n')
            if line.startswith('data:'):
                cur.append(line[5:].lstrip())
            elif not line and cur:
                text = ''.join(cur)
                cur = []
                if text.strip():
                    try:
                        events.append(json.loads(text))
                    except Exception:
                        pass
        if cur and ''.join(cur).strip():
            try:
                events.append(json.loads(''.join(cur)))
            except Exception:
                pass
    return events


def summarize(events):
    out = {'answer': '', 'route': None, 'tools': [], 'finished': False, 'error': None, 'frames': len(events)}
    for e in events:
        t = e.get('type')
        if t == 'TEXT_MESSAGE_CONTENT':
            out['answer'] += e.get('delta') or ''
        elif t == 'CUSTOM' and e.get('name') == 'AGENT_ROUTE':
            out['route'] = e.get('value') or {}
        elif t == 'TOOL_CALL_START':
            out['tools'].append({'id': e.get('toolCallId'), 'name': e.get('toolCallName')})
        elif t == 'RUN_FINISHED':
            out['finished'] = True
        elif t == 'RUN_ERROR':
            out['error'] = e.get('message')
    return out


def get(path):
    req = urllib.request.Request(BASE + path, headers={'Cookie': 'V2X_SESSION=' + COOKIE})
    with urllib.request.urlopen(req, timeout=30) as resp:
        return json.loads(resp.read().decode('utf-8'))


def main():
    login()
    print('login OK')
    events = chat()
    result = summarize(events)
    with open('/tmp/chat_result.json', 'w', encoding='utf-8') as f:
        json.dump(result, f, ensure_ascii=False, indent=1)
    print('frames=%s finished=%s error=%s' % (result['frames'], result['finished'], result['error']))
    print('route=%s' % (result['route'] or {}).get('route'))
    print('tools=%s' % [t['name'] for t in result['tools']])
    assert result['finished'], 'run not finished'
    assert result['tools'], 'no tool calls'

    # 对话后查任务列表：应出现新任务，且 cron 每天 08:00
    tasks = get('/ag-ui/task/mine')
    cron_tasks = [t for t in tasks if t.get('schedule_enabled')]
    assert cron_tasks, 'no scheduled task created'
    latest = cron_tasks[0]
    print('task id=%s cron=%s steps=%s title_bytes=%s' % (
        latest['id'], latest['cron_expr'], latest['step_count'],
        len(latest['title'].encode('utf-8'))))
    assert '0 0 8' in (latest['cron_expr'] or ''), 'cron not daily-8am: %s' % latest['cron_expr']
    with open('/tmp/task_mine.json', 'w', encoding='utf-8') as f:
        json.dump(tasks, f, ensure_ascii=False, indent=1)
    print('SAVE_TASK CHAT: ALL PASS')


if __name__ == '__main__':
    main()

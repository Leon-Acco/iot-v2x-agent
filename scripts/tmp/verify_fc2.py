# -*- coding: utf-8 -*-
# function calling 改造验证脚本：中文正常问题 + 无关问题拒答
import json, uuid, urllib.request, http.cookiejar, sys
sys.stdout.reconfigure(encoding='utf-8')

BASE = 'http://localhost:8080'

def run_q(opener, question, label):
    body = {'threadId': 't-verify-fc', 'runId': uuid.uuid4().hex,
            'messages': [{'id': uuid.uuid4().hex, 'role': 'user', 'content': question}]}
    req = urllib.request.Request(BASE + '/agui/run',
        data=json.dumps(body).encode(), headers={'Content-Type': 'application/json'})
    events, cap, texts = [], None, []
    with opener.open(req, timeout=120) as r:
        for raw in r.read().decode('utf-8', 'ignore').splitlines():
            if raw.startswith('data:'):
                f = json.loads(raw[5:])
                t = f.get('type')
                if t == 'TOOL_CALL_START':
                    cap = f.get('toolCallName')
                if t == 'TEXT_MESSAGE_CONTENT':
                    texts.append(f.get('delta', ''))
                if t not in ('TEXT_MESSAGE_CONTENT',):
                    events.append(t)
    print('===', label, '===')
    print('capability:', cap)
    print('events:', ' -> '.join(dict.fromkeys(events)))
    print('conclusion:', ''.join(texts)[:300])
    print()

cj = http.cookiejar.CookieJar()
opener = urllib.request.build_opener(urllib.request.HTTPCookieProcessor(cj))
login = urllib.request.Request(BASE + '/api/auth/login',
    data=json.dumps({'username': 'admin', 'password': 'admin123'}).encode(),
    headers={'Content-Type': 'application/json'})
print('login:', json.loads(opener.open(login).read()))

run_q(opener, '近7天各类型告警次数', 'normal-alarm')
run_q(opener, '今天天气怎么样', 'out-of-scope-reject')

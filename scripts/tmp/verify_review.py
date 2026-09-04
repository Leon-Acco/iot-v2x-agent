# -*- coding: utf-8 -*-
# 异常自动触发复核验证：创建单车巡检任务 -> 执行 -> 检查报告标记/复核步骤/复核结论段
import json, os, sys, urllib.request, http.cookiejar
sys.stdout.reconfigure(encoding='utf-8')

BASE = os.environ.get('V2X_BASE', 'http://localhost:8081')

def main():
    cj = http.cookiejar.CookieJar()
    opener = urllib.request.build_opener(urllib.request.HTTPCookieProcessor(cj))
    login = urllib.request.Request(BASE + '/api/auth/login',
        data=json.dumps({'username': 'admin', 'password': 'admin123'}).encode(),
        headers={'Content-Type': 'application/json'})
    opener.open(login).read()
    print('login ok')

    # 创建单车巡检任务（粤C10003 有真实告警数据，报告大概率判定异常）
    body = {
        'title': 'YueC10003 weiDu xunJian fuhe yanZheng',
        'sourceQuestion': 'YueC10003 jin7tian gaojing',
        'steps': [
            {'id': 'real_alarm_count#1', 'name': '告警类型统计',
             'args': {'vehicle': '粤C10003', 'time_range': '近7天'}},
        ],
    }
    req = urllib.request.Request(BASE + '/ag-ui/task',
        data=json.dumps(body).encode(), headers={'Content-Type': 'application/json'})
    data = json.loads(opener.open(req, timeout=30).read())
    assert data.get('id'), 'create failed: %s' % data
    task_id = data['id']
    print('task created:', task_id, '| steps:', len(data['steps']), '| skipped:', len(data.get('skipped') or []))

    # 执行（报告 + 可能的自动复核，最长 ~3 分钟）
    req = urllib.request.Request(BASE + '/ag-ui/task/%s/run' % task_id,
        data=b'{}', headers={'Content-Type': 'application/json'})
    run = json.loads(opener.open(req, timeout=240).read())
    print('run status:', run.get('status'), '| elapsedMs:', run.get('elapsedMs'))

    conclusion = run.get('conclusion') or ''
    steps = run.get('steps') or []
    review_steps = [s for s in steps if s.get('review')]
    has_mark = '<复核' in conclusion
    has_review_section = '【自动复核' in conclusion

    print('--- conclusion (tail) ---')
    print(conclusion[-600:] if conclusion else '(none)')
    print('--- steps ---')
    for s in steps:
        print(' ', s.get('seq'), s.get('displayName'), s.get('status'),
              (s.get('result') or {}).get('rowCount'), 'rows')
    print()
    print('check1 report-mark-or-review:', 'PASS' if (has_mark or has_review_section) else 'NOTE(no anomaly judged)')
    print('check2 review-step-appended:', 'PASS' if review_steps else 'FAIL')
    if review_steps:
        print('check3 review-conclusion:', 'PASS' if has_review_section else 'FAIL')
        print('check4 review-step-rows:', 'PASS' if (review_steps[0].get('result') or {}).get('rowCount', 0) > 0 else 'FAIL')

if __name__ == '__main__':
    main()

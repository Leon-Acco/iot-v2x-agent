# -*- coding: utf-8 -*-
"""任务卡 2.0 运行时验证脚本：登录 → 精炼创建 → 一键执行 → 定时预览
用法：python scripts/verify_task_api.py [phase]  phase=run(默认)/schedule
"""
import json
import os
import sys
import urllib.request

BASE = os.environ.get('V2X_BASE', 'http://localhost:8080')
COOKIE = None


def post(path, body):
    req = urllib.request.Request(BASE + path, data=json.dumps(body).encode('utf-8'),
                                 headers={'Content-Type': 'application/json'}, method='POST')
    return send(req)


def get(path):
    req = urllib.request.Request(BASE + path, method='GET')
    return send(req)


def send(req):
    global COOKIE
    if COOKIE:
        req.add_header('Cookie', 'V2X_SESSION=' + COOKIE)
    try:
        with urllib.request.urlopen(req, timeout=180) as resp:
            set_cookie = resp.headers.get('Set-Cookie')
            if set_cookie and 'V2X_SESSION=' in set_cookie:
                COOKIE = set_cookie.split('V2X_SESSION=')[1].split(';')[0]
            return resp.status, json.loads(resp.read().decode('utf-8'))
    except urllib.error.HTTPError as e:
        return e.code, json.loads(e.read().decode('utf-8'))


def show(tag, code, data, limit=900):
    text = json.dumps(data, ensure_ascii=False, indent=1)
    print('== %s [HTTP %s] ==' % (tag, code))
    print(text[:limit])
    print()


def main():
    phase = sys.argv[1] if len(sys.argv) > 1 else 'run'

    code, data = post('/api/auth/login', {'username': 'admin', 'password': 'admin123'})
    assert code == 200 and data.get('success'), 'login failed: %s' % data
    print('login OK')

    if phase == 'run':
        # 1) 精炼创建：1 个可固化帧（告警类型统计，相对词+车牌）+ 1 个无映射帧（车辆档案）
        body = {
            'title': '粤C10003 周度告警体检',
            'sourceQuestion': '粤C10003 近7天告警',
            'steps': [
                {'id': 'real_alarm_count#1', 'name': '告警类型统计',
                 'args': {'vehicle': '粤C10003', 'time_range': '近7天'}},
                {'id': 'query_vehicle_info#2', 'name': '车辆档案',
                 'args': {'vehicle': '粤C10003'}},
            ],
        }
        code, data = post('/ag-ui/task', body)
        show('create', code, data)
        assert code == 200 and data.get('id'), 'create failed'
        assert len(data['steps']) == 1, 'expect 1 step refined'
        assert len(data['skipped']) == 1, 'expect 1 skipped (query_vehicle_info)'
        task_id = data['id']

        # 2) 一键执行（同步返回每步表格 + 分析报告 conclusion）
        code, data = post('/ag-ui/task/%s/run' % task_id, {})
        show('run', code, data, 1600)
        assert code == 200, 'run failed'
        assert data.get('status') in ('SUCCESS', 'PARTIAL'), 'run status: %s' % data.get('status')
        assert data.get('conclusion'), 'run conclusion missing (analyze_report default on)'
        print('conclusion preview:', str(data.get('conclusion'))[:200])
        run_id = data.get('runId')

        # 3) 执行历史
        code, data = get('/ag-ui/task/%s/runs' % task_id)
        show('runs', code, data, 700)

        # 4) 历史 run 详情（conclusion 应随详情返回）
        code, data = get('/ag-ui/task-run/%s' % run_id)
        show('run detail', code, data, 700)
        assert data.get('conclusion'), 'run detail conclusion missing'
        print('PHASE RUN: ALL PASS')

    elif phase == 'schedule':
        # 定时：预览 + 启用每分钟 cron + 列表确认
        code, data = get('/ag-ui/task/sched/preview?cron=' + urllib.request.quote('0 * * * * *'))
        show('cron preview', code, data, 500)

        code, data = get('/ag-ui/task/mine')
        assert code == 200 and data, 'no tasks'
        task_id = data[0]['id']
        code, data = post('/ag-ui/task/%s/schedule' % task_id, {'enabled': True, 'cronExpr': '0 * * * * *'})
        show('schedule enable', code, data, 500)

        code, data = get('/ag-ui/task/mine')
        show('mine after schedule', code, data, 900)
        print('PHASE SCHEDULE: ALL PASS (wait 60s then re-run phase=cron_check)')

    elif phase == 'cron_check':
        code, data = get('/ag-ui/task/mine')
        assert code == 200 and data, 'no tasks'
        task_id = data[0]['id']
        code, data = get('/ag-ui/task/%s/runs' % task_id)
        show('runs after cron window', code, data, 1200)
        cron_runs = [r for r in data if r.get('trigger_type') == 'cron']
        assert cron_runs, 'no cron run recorded'
        print('PHASE CRON_CHECK: PASS, cron runs = %s' % len(cron_runs))


if __name__ == '__main__':
    main()

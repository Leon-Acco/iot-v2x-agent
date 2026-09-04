// 任务卡 2.0（可执行任务）：列表 / 从会话帧创建 / 一键执行 / 定时设置 / 执行历史
export function useTasks() {
  const api = useApi()
  const tasks = ref([])

  async function loadMine() {
    try {
      const data = await api.get('/ag-ui/task/mine')
      tasks.value = Array.isArray(data) ? data : []
    } catch (e) {
      tasks.value = []
    }
  }

  // 从会话工具帧精炼创建任务（steps = 本轮全部工具帧，多步整链固化）
  function createFromFrames(payload) {
    return api.post('/ag-ui/task', payload)
  }

  // 一键执行：同步返回 RunResponse（每步表格结果）
  function run(id) {
    return api.post('/ag-ui/task/' + id + '/run', {})
  }

  function remove(id) {
    return api.del('/ag-ui/task/' + id)
  }

  // 定时设置：{ enabled, cronExpr }
  function setSchedule(id, body) {
    return api.post('/ag-ui/task/' + id + '/schedule', body)
  }

  // cron 未来 3 次触发预览
  async function previewCron(cron) {
    const data = await api.get('/ag-ui/task/sched/preview?cron=' + encodeURIComponent(cron))
    return (data && data.next) || []
  }

  function loadRuns(id, limit) {
    return api.get('/ag-ui/task/' + id + '/runs' + (limit ? '?limit=' + limit : ''))
  }

  function loadRun(runId) {
    return api.get('/ag-ui/task-run/' + runId)
  }

  return { tasks, loadMine, createFromFrames, run, remove, setSchedule, previewCron, loadRuns, loadRun }
}

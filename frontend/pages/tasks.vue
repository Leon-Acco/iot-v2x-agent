<template>
  <!-- 任务中心：可执行任务列表 + 执行结果/历史抽屉 + 定时设置 -->
  <AppShell title="任务中心">
    <div class="tasks-page">
      <div class="page-hint">
        把运营工作台里跑通的查询流程「存为任务」，即可在这里一键重跑、设置定时自动执行。
        定时任务按创建者当前数据权限执行，权限回收自动收紧范围。
      </div>
      <TaskListPanel
        :tasks="tasks"
        @refresh="loadMine"
        @run-done="openRun"
        @schedule="openSchedule"
        @history="openHistory"
      />
    </div>

    <!-- 执行结果抽屉（手动执行返回 / 历史详情共用） -->
    <DrawerPanel v-model="runDrawer" title="执行结果" width="720px">
      <TaskRunView v-if="currentRun" :run="currentRun" />
    </DrawerPanel>

    <!-- 执行历史抽屉：run 列表，点行看详情 -->
    <DrawerPanel v-model="historyDrawer" :title="historyTitle" width="560px">
      <div v-if="!historyRuns.length" class="hist-empty">还没有执行记录</div>
      <div
        v-for="r in historyRuns" :key="r.id"
        class="hist-row" :class="String(r.status).toLowerCase()"
        @click="showRun(r.id)"
      >
        <span class="hist-status">{{ statusLabel(r.status) }}</span>
        <span class="hist-trigger">{{ r.trigger_type === 'cron' ? '定时' : '手动' }} · {{ r.triggered_by }}</span>
        <span class="hist-time">{{ fmtTime(r.started_at) }}</span>
        <span class="hist-elapsed">{{ r.elapsed_ms != null ? r.elapsed_ms + 'ms' : '' }}</span>
      </div>
      <div v-if="historyError" class="hist-error">{{ historyError }}</div>
    </DrawerPanel>

    <TaskScheduleDialog v-model="scheduleDialog" :task="scheduleTask" @saved="loadMine" />
  </AppShell>
</template>

<script setup>
const { tasks, loadMine, loadRuns, loadRun } = useTasks()

// 执行结果抽屉
const runDrawer = ref(false)
const currentRun = ref(null)

// 历史抽屉
const historyDrawer = ref(false)
const historyRuns = ref([])
const historyTitle = ref('执行历史')
const historyError = ref('')

// 定时对话框
const scheduleDialog = ref(false)
const scheduleTask = ref(null)

onMounted(() => { loadMine() })

function openRun(res) {
  currentRun.value = res
  runDrawer.value = true
}

async function openHistory(t) {
  historyTitle.value = '执行历史 · ' + t.title
  historyRuns.value = []
  historyError.value = ''
  historyDrawer.value = true
  try {
    const rows = await loadRuns(t.id, 20)
    historyRuns.value = Array.isArray(rows) ? rows : []
  } catch (e) {
    historyError.value = e.message || '加载失败'
  }
}

async function showRun(runId) {
  try {
    currentRun.value = await loadRun(runId)
    historyDrawer.value = false
    runDrawer.value = true
  } catch (e) {
    historyError.value = e.message || '加载失败'
  }
}

function openSchedule(t) {
  scheduleTask.value = t
  scheduleDialog.value = true
}

function statusLabel(s) {
  return { RUNNING: '执行中', SUCCESS: '成功', PARTIAL: '部分成功', FAILED: '失败', SKIPPED: '跳过' }[s] || s || '-'
}

function fmtTime(t) {
  if (!t) return '-'
  const d = new Date(t)
  return isNaN(d.getTime()) ? String(t) : d.toLocaleString('zh-CN', { hour12: false })
}
</script>

<style scoped>
.tasks-page { max-width: 860px; }
.page-hint {
  font-size: 12px; color: var(--text-3); line-height: 1.8; margin-bottom: 14px;
  background: #F3F4F6; border-radius: 12px; padding: 10px 14px;
}
.hist-empty { color: var(--text-3); font-size: 12px; text-align: center; padding: 24px 8px; }
.hist-row {
  display: flex; align-items: center; gap: 10px; padding: 10px 12px; margin-bottom: 8px;
  border: 1px solid var(--line); border-radius: 10px; cursor: pointer; font-size: 12px;
  transition: border-color .15s, background .15s;
}
.hist-row:hover { border-color: rgba(23, 160, 94, .4); background: rgba(23, 160, 94, .04); }
.hist-status { font-weight: 700; width: 52px; flex-shrink: 0; }
.hist-row.success .hist-status { color: var(--green-deep); }
.hist-row.partial .hist-status { color: var(--orange, #D97706); }
.hist-row.failed .hist-status { color: var(--danger); }
.hist-row.skipped .hist-status { color: var(--text-3); }
.hist-row.running .hist-status { color: var(--text-2); }
.hist-trigger { color: var(--text-2); }
.hist-time { color: var(--text-3); margin-left: auto; }
.hist-elapsed { color: var(--text-3); font-family: "SF Mono", Consolas, monospace; }
.hist-error { color: var(--danger); font-size: 12px; margin-top: 8px; }
</style>

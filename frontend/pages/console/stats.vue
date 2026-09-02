<template>
  <!-- P7 效果看板与审计：看板 / 审计 / 反馈三 tab -->
  <AppShell title="效果看板与审计">
    <div class="stats-page">
      <div class="page-tabs panel-card">
        <button v-for="t in tabs" :key="t.key" class="tab-btn" :class="{ active: tab === t.key }" @click="switchTab(t.key)">
          {{ t.label }}
        </button>
      </div>

      <template v-if="tab === 'board'">
        <div class="stat-row">
          <StatCard label="总运行" :value="ov.runs.total" icon="▦" />
          <StatCard label="成功率" :value="successRate + '%'" accent="#22C55E" icon="✓" />
          <StatCard label="平均延迟" :value="(ov.runs.avg_elapsed_ms || 0) + 'ms'" accent="#F59E0B" icon="⏱" />
          <StatCard label="好评率" :value="upRate" icon="👍" />
        </div>
        <div class="board-grid">
          <PanelCard title="高频能力 Top5">
            <div v-for="(c, i) in ov.topCapabilities" :key="i" class="rank-item">
              <div class="rank-head">
                <span class="mono">{{ c.capability_id }}</span>
                <span class="rank-num">{{ c.cnt }} 次 · {{ c.avg_ms }}ms</span>
              </div>
              <div class="rank-track"><div class="rank-bar" :style="{ width: capPct(c.cnt) + '%' }"></div></div>
            </div>
          </PanelCard>
          <PanelCard title="错误码分布">
            <div v-if="!ov.topErrors || !ov.topErrors.length" class="empty">暂无错误</div>
            <div v-for="(e, i) in ov.topErrors || []" :key="i" class="err-row">
              <span class="mono err-code">{{ e.error_code }}</span>
              <span class="err-cnt">{{ e.cnt }} 次</span>
            </div>
            <div class="board-note">
              今日运行 {{ ov.today.total || 0 }} 次，拒答 {{ ov.runs.refused }} · 失败 {{ ov.runs.failed }}
            </div>
          </PanelCard>
        </div>
        <PanelCard title="未覆盖问题榜" class="uncovered-card">
          <template #actions><span class="hint">驱动 capability 扩充</span></template>
          <table class="uc-table">
            <thead><tr><th>问题</th><th>次数</th></tr></thead>
            <tbody>
              <tr v-for="(q, i) in ov.uncoveredQuestions" :key="i">
                <td>{{ q.question }}</td>
                <td class="mono">{{ q.cnt }}</td>
              </tr>
            </tbody>
          </table>
          <div v-if="!ov.uncoveredQuestions || !ov.uncoveredQuestions.length" class="empty">暂无未覆盖问题</div>
        </PanelCard>
      </template>

      <div v-if="tab === 'audit'" class="panel-card tab-panel">
        <AuditTable />
      </div>

      <div v-if="tab === 'feedback'" class="panel-card tab-panel">
        <FeedbackList />
      </div>
    </div>
  </AppShell>
</template>

<script setup>
const api = useApi()
const route = useRoute()
const tab = ref('board')
const ov = ref({
  runs: {}, today: {}, feedback: {},
  topCapabilities: [], topErrors: [], uncoveredQuestions: []
})

const tabs = [
  { key: 'board', label: '效果看板' },
  { key: 'audit', label: '调用审计' },
  { key: 'feedback', label: '反馈列表' }
]

const successRate = computed(() => {
  const r = ov.value.runs
  return r.total ? Math.round((r.success / r.total) * 100) : 0
})
const upRate = computed(() => {
  const f = ov.value.feedback
  if (!f.total) return '-'
  return Math.round((f.up / f.total) * 100) + '%'
})
const maxCapCnt = computed(() =>
  Math.max(1, ...(ov.value.topCapabilities || []).map(c => c.cnt))
)
function capPct(cnt) {
  return Math.max(6, Math.round((cnt / maxCapCnt.value) * 100))
}

function switchTab(key) {
  tab.value = key
}

onMounted(async () => {
  if (route.query.tab === 'audit') tab.value = 'audit'
  try {
    const data = await api.get('/admin/stats/overview')
    if (data) ov.value = Object.assign(ov.value, data)
  } catch (e) { /* 保持空态 */ }
})
</script>

<style scoped>
.stats-page { display: flex; flex-direction: column; gap: 12px; }
.page-tabs { display: flex; gap: 6px; padding: 8px 12px; }
.tab-btn {
  border: none; background: transparent; padding: 7px 14px; border-radius: 8px;
  font-size: 13px; color: var(--text-2); cursor: pointer;
}
.tab-btn.active { background: var(--primary-light); color: var(--primary); font-weight: 600; }
.stat-row { display: grid; grid-template-columns: repeat(4, 1fr); gap: 12px; }
.board-grid { display: grid; grid-template-columns: 1fr 1fr; gap: 12px; }
.rank-item { margin-bottom: 10px; }
.rank-head { display: flex; justify-content: space-between; font-size: 12px; margin-bottom: 4px; }
.mono { font-family: "SF Mono", Consolas, monospace; color: var(--text-2); }
.rank-num { color: var(--text-3); font-family: var(--font-num); }
.rank-track { height: 6px; border-radius: 3px; background: #F0F0F0; overflow: hidden; }
.rank-bar { height: 100%; border-radius: 3px; background: linear-gradient(90deg, #93C5FD, #2563EB); }
.err-row { display: flex; justify-content: space-between; padding: 6px 0; font-size: 12px; }
.err-code { color: var(--danger); }
.err-cnt { color: var(--text-3); }
.board-note { margin-top: 12px; font-size: 11px; color: var(--text-3); }
.empty { color: var(--text-3); font-size: 12px; padding: 14px 0; text-align: center; }
.uncovered-card { margin-top: 0; }
.hint { font-size: 11px; color: var(--text-3); }
.uc-table { width: 100%; border-collapse: collapse; font-size: 12px; }
.uc-table th {
  text-align: left; padding: 6px 10px; color: var(--text-3);
  border-bottom: 1px solid #E5E5E5; font-weight: 600;
}
.uc-table td { padding: 6px 10px; border-bottom: 1px solid #F3F4F6; }
.tab-panel { padding: 14px 16px; }
</style>

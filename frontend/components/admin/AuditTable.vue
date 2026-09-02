<template>
  <!-- P7 调用审计：状态筛选 + 导出 CSV -->
  <div>
    <div class="audit-toolbar">
      <select v-model="statusFilter" class="status-filter">
        <option value="">全部状态</option>
        <option value="SUCCESS">SUCCESS</option>
        <option value="REFUSED">REFUSED</option>
        <option value="FAILED">FAILED</option>
        <option value="CANCELLED">CANCELLED</option>
      </select>
      <span class="count">共 {{ filtered.length }} 条</span>
      <button class="btn btn-primary export-btn" @click="exportCsv">导出 CSV</button>
    </div>
    <table class="audit-table">
      <thead>
        <tr>
          <th>时间</th><th>用户</th><th>profile</th><th>问题</th>
          <th>能力</th><th>状态</th><th>耗时</th><th>行数</th>
        </tr>
      </thead>
      <tbody>
        <tr v-for="(r, i) in filtered" :key="i">
          <td class="nowrap">{{ fmt(r.created_at) }}</td>
          <td>{{ r.user_id }}</td>
          <td class="mono">{{ r.profile_id }}</td>
          <td class="q-cell" :title="r.question">{{ r.question }}</td>
          <td class="mono">{{ r.capability_id || '-' }}</td>
          <td><StatusPill :status="r.status" :label="r.status" /></td>
          <td class="mono">{{ r.elapsed_ms }}ms</td>
          <td class="mono">{{ r.row_count }}</td>
        </tr>
      </tbody>
    </table>
    <div v-if="!filtered.length" class="empty">暂无审计记录</div>
  </div>
</template>

<script setup>
const api = useApi()
const rows = ref([])
const statusFilter = ref('')

const filtered = computed(() =>
  statusFilter.value ? rows.value.filter(r => r.status === statusFilter.value) : rows.value
)

function fmt(t) {
  if (!t) return '-'
  const d = new Date(t)
  return isNaN(d.getTime()) ? String(t) : d.toLocaleString('zh-CN', { hour12: false })
}

function exportCsv() {
  const headers = [
    { key: 'created_at', label: '时间' },
    { key: 'user_id', label: '用户' },
    { key: 'profile_id', label: 'profile' },
    { key: 'question', label: '问题' },
    { key: 'capability_id', label: '能力' },
    { key: 'status', label: '状态' },
    { key: 'error_code', label: '错误码' },
    { key: 'elapsed_ms', label: '耗时ms' },
    { key: 'row_count', label: '行数' },
    { key: 'trace_id', label: 'trace_id' }
  ]
  downloadCsv('audit-runs.csv', headers, filtered.value)
}

onMounted(async () => {
  try {
    const data = await api.get('/admin/audit/runs')
    rows.value = Array.isArray(data) ? data : []
  } catch (e) { /* 保持空态 */ }
})
</script>

<style scoped>
.audit-toolbar { display: flex; align-items: center; gap: 10px; margin-bottom: 12px; }
.status-filter {
  padding: 7px 10px; font-size: 12px; border: 1px solid #E5E5E5;
  border-radius: 8px; background: #F3F3F5; outline: none; color: var(--text-2);
}
.count { font-size: 12px; color: var(--text-3); }
.export-btn { margin-left: auto; padding: 6px 14px; font-size: 12px; }
.audit-table { width: 100%; border-collapse: collapse; font-size: 12px; }
.audit-table th {
  text-align: left; padding: 8px 10px; color: var(--text-3); font-weight: 600;
  border-bottom: 1px solid #E5E5E5; white-space: nowrap;
}
.audit-table td { padding: 7px 10px; border-bottom: 1px solid #F3F4F6; }
.audit-table tbody tr:hover { background: #F3F3F5; }
.mono { font-family: "SF Mono", Consolas, monospace; font-size: 11px; color: var(--text-2); }
.nowrap { white-space: nowrap; }
.q-cell { max-width: 260px; overflow: hidden; text-overflow: ellipsis; white-space: nowrap; }
.empty { text-align: center; color: var(--text-3); padding: 30px 0; }
</style>

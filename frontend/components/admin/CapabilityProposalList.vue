<template>
  <!-- 能力审批中心：待审单卡片（定义摘要 + SQL 预览）通过/驳回（镜像记忆审批） -->
  <div>
    <div class="filter-row">
      <button
        v-for="f in statusFilters" :key="f.value"
        class="filter-chip" :class="{ active: status === f.value }"
        @click="status = f.value; load()"
      >{{ f.label }}</button>
      <span class="total">共 {{ items.length }} 张</span>
    </div>

    <div v-if="loadError" class="load-error">{{ loadError }}</div>
    <div v-else-if="!items.length" class="empty">暂无审批单</div>

    <div v-for="p in items" :key="p.id" class="proposal-card">
      <div class="p-head">
        <span class="p-id mono">{{ p.capability_id }}</span>
        <span class="action-tag" :class="p.action">{{ p.action === 'create' ? '新建' : '上线' }}</span>
        <span class="status-tag" :class="p.status">{{ statusText(p.status) }}</span>
      </div>

      <div v-if="p.payload" class="p-summary">
        <div class="s-item"><span>显示名</span>{{ p.payload.display }}</div>
        <div class="s-item"><span>域</span>{{ p.payload.domain }}</div>
        <div class="s-item"><span>图表</span>{{ p.payload.chartHint }}</div>
        <div class="s-item"><span>来源表</span>{{ (p.payload.sourceTables || []).join(', ') || '-' }}</div>
      </div>

      <details v-if="p.payload && p.payload.sqlTemplate" class="sql-details">
        <summary>SQL 预览</summary>
        <pre class="sql-view">{{ p.payload.sqlTemplate }}</pre>
      </details>

      <div class="p-meta">
        <span>提议人：{{ p.proposed_by }}</span>
        <span>{{ fmt(p.created_at) }}</span>
        <template v-if="p.reviewed_by">
          <span>审批人：{{ p.reviewed_by }}</span>
          <span>{{ fmt(p.reviewed_at) }}</span>
        </template>
      </div>

      <div v-if="p.reject_reason" class="reject-reason">驳回理由：{{ p.reject_reason }}</div>

      <div v-if="p.status === 'PENDING'" class="p-actions">
        <button class="btn" @click="$emit('dryrun', p.capability_id)">试跑</button>
        <button class="btn btn-primary" @click="approve(p)">通过并上线</button>
        <button class="btn reject" @click="reject(p)">驳回</button>
      </div>
    </div>
  </div>
</template>

<script setup>
const emit = defineEmits(['changed', 'dryrun'])
const api = useApi()

const items = ref([])
const status = ref('PENDING')
const loadError = ref('')
const statusFilters = [
  { value: 'PENDING', label: '待审批' },
  { value: 'APPROVED', label: '已通过' },
  { value: 'REJECTED', label: '已驳回' },
  { value: '', label: '全部' }
]

function statusText(s) {
  return { PENDING: '待审批', APPROVED: '已通过', REJECTED: '已驳回' }[s] || s
}

function fmt(t) {
  if (!t) return '-'
  const d = new Date(t)
  return isNaN(d.getTime()) ? String(t) : d.toLocaleString('zh-CN', { hour12: false })
}

async function load() {
  loadError.value = ''
  try {
    const data = await api.get('/admin/capability-proposals' + (status.value ? '?status=' + status.value : ''))
    items.value = Array.isArray(data) ? data : []
  } catch (e) {
    loadError.value = e.message || '加载失败'
  }
}

async function approve(p) {
  try {
    const res = await api.post('/admin/capability-proposals/' + p.id + '/approve')
    if (res && res.success === false) { alert(res.message || '操作失败'); return }
    await load()
    emit('changed')
  } catch (e) { alert(e.message) }
}

async function reject(p) {
  const reason = window.prompt('驳回理由（必填）')
  if (!reason) return
  try {
    await api.post('/admin/capability-proposals/' + p.id + '/reject', { reason })
    await load()
    emit('changed')
  } catch (e) { alert(e.message) }
}

defineExpose({ load })
onMounted(load)
</script>

<style scoped>
.filter-row { display: flex; align-items: center; gap: 8px; margin-bottom: 14px; }
.filter-chip {
  padding: 5px 14px; font-size: 12px; border: 1px solid #E5E5E5; border-radius: 14px;
  background: #fff; color: var(--text-2); cursor: pointer; transition: all .15s ease;
}
.filter-chip:hover { border-color: var(--primary-600); color: var(--primary); }
.filter-chip.active { background: var(--primary); border-color: var(--primary); color: #fff; }
.total { font-size: 12px; color: var(--text-3); margin-left: auto; }

/* ≤760：筛选按钮独占一行，「共 N 张」换行到下一行；卡内英文/数字长 token 可折行 */
@media (max-width: 760px) {
  .filter-row { flex-wrap: wrap; }
  .filter-chip { white-space: nowrap; }
  .total { margin-left: 0; flex-basis: 100%; }
  .p-id, .s-item, .p-meta, .reject-reason { overflow-wrap: anywhere; }
}
.load-error { color: var(--danger); padding: 30px; text-align: center; }
.empty { text-align: center; color: var(--text-3); padding: 40px 0; font-size: 13px; }

.proposal-card {
  border: 1px solid #EEF2F0; border-radius: 12px; padding: 14px 16px; margin-bottom: 12px;
  background: #fff; transition: box-shadow .18s ease;
}
.proposal-card:hover { box-shadow: var(--card-shadow-hover); }
.p-head { display: flex; align-items: center; gap: 8px; margin-bottom: 10px; }
.p-id { font-size: 13px; font-weight: 600; }
.mono { font-family: "SF Mono", Consolas, monospace; }
.action-tag { font-size: 11px; padding: 2px 8px; border-radius: 4px; background: #eef2ff; color: #4F46E5; }
.action-tag.online { background: var(--primary-light); color: var(--primary-deep); }
.status-tag { font-size: 11px; padding: 2px 8px; border-radius: 4px; margin-left: auto; }
.status-tag.PENDING { background: #fffbeb; color: #b45309; }
.status-tag.APPROVED { background: var(--primary-light); color: var(--primary-deep); }
.status-tag.REJECTED { background: #fee2e2; color: #b91c1c; }

.p-summary { display: grid; grid-template-columns: repeat(4, 1fr); gap: 8px; margin-bottom: 10px; }
.s-item { background: #F9FAFB; border-radius: 8px; padding: 7px 10px; font-size: 12px; display: flex; flex-direction: column; gap: 2px; }
.s-item span { font-size: 11px; color: var(--text-3); }

.sql-details { margin-bottom: 10px; }
.sql-details summary { font-size: 12px; color: var(--primary); cursor: pointer; user-select: none; }
.sql-view {
  background: #0F1B2D; color: #d7e4dd; font-family: "SF Mono", Consolas, monospace;
  font-size: 12px; line-height: 1.7; padding: 12px 14px; border-radius: 8px;
  margin-top: 8px; overflow-x: auto; white-space: pre-wrap; word-break: break-all;
}

.p-meta { display: flex; gap: 16px; font-size: 12px; color: var(--text-3); }
.reject-reason { font-size: 12px; color: var(--danger); margin-top: 8px; }
.p-actions { display: flex; gap: 10px; margin-top: 12px; justify-content: flex-end; }
.reject { color: var(--danger); }
.reject:hover { background: rgba(239,68,68,.08); color: var(--danger); }
</style>

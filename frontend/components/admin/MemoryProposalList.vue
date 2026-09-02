<template>
  <!-- P6 提议审批：通过 / 驳回（必填理由） -->
  <div>
    <div v-if="!proposals.length" class="empty">暂无待审提议</div>
    <div v-for="p in proposals" :key="p.id" class="proposal-card">
      <div class="p-content">{{ p.content }}</div>
      <div class="p-meta">
        <span class="pill">{{ p.scope_type }}<template v-if="p.scope_key">:{{ p.scope_key }}</template></span>
        <span>提议人：{{ p.proposed_by }}</span>
        <span>{{ fmt(p.created_at) }}</span>
        <StatusPill :status="p.status" />
      </div>
      <div v-if="p.status === 'PENDING'" class="p-actions">
        <button class="btn btn-primary" @click="approve(p)">通过</button>
        <button class="btn reject" @click="reject(p)">驳回</button>
      </div>
      <div v-else-if="p.reject_reason" class="reject-reason">驳回理由：{{ p.reject_reason }}</div>
    </div>
  </div>
</template>

<script setup>
const props = defineProps({
  proposals: { type: Array, default: () => [] }
})
const emit = defineEmits(['changed'])
const api = useApi()

function fmt(t) {
  if (!t) return '-'
  const d = new Date(t)
  return isNaN(d.getTime()) ? String(t) : d.toLocaleString('zh-CN', { hour12: false })
}

async function approve(p) {
  try {
    const res = await api.post('/admin/memory/proposals/' + p.id + '/approve')
    if (res && res.success === false) { alert(res.message || '操作失败'); return }
    emit('changed')
  } catch (e) { alert(e.message) }
}

async function reject(p) {
  const reason = window.prompt('驳回理由（必填）')
  if (!reason) return
  try {
    await api.post('/admin/memory/proposals/' + p.id + '/reject', { reason })
    emit('changed')
  } catch (e) { alert(e.message) }
}
</script>

<style scoped>
.empty { text-align: center; color: var(--text-3); padding: 40px 0; }
.proposal-card {
  border: 1px solid #F0F0F0; border-radius: 12px; padding: 12px 14px; margin-bottom: 10px;
  background: #FFFFFF;
}
.p-content { font-size: 13px; line-height: 1.7; }
.p-meta {
  display: flex; gap: 12px; align-items: center; flex-wrap: wrap;
  margin-top: 8px; font-size: 11px; color: var(--text-3);
}
.pill { background: #F3F4F6; border-radius: 12px; padding: 2px 8px; }
.p-actions { margin-top: 10px; display: flex; gap: 8px; }
.p-actions .btn { padding: 4px 14px; font-size: 12px; }
.btn.reject { color: var(--danger); }
.btn.reject:hover { background: rgba(239,68,68,.08); }
.reject-reason { margin-top: 8px; font-size: 11px; color: var(--danger); }
</style>

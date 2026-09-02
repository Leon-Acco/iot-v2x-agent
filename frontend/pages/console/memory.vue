<template>
  <!-- P6 记忆治理台：提议审批 / 记忆库+ACL / 删除申请 -->
  <AppShell title="记忆治理台">
    <div class="mem-page panel-card">
      <div class="page-tabs">
        <button v-for="t in tabs" :key="t.key" class="tab-btn" :class="{ active: tab === t.key }" @click="switchTab(t.key)">
          {{ t.label }}<span v-if="t.key === 'proposals' && pendingCount" class="badge">{{ pendingCount }}</span>
        </button>
      </div>

      <MemoryProposalList v-if="tab === 'proposals'" :proposals="proposals" @changed="loadProposals" />
      <MemoryAclPanel v-if="tab === 'acl'" ref="aclPanel" />

      <div v-if="tab === 'removal'">
        <div v-if="!removals.length" class="empty">暂无删除申请</div>
        <div v-for="r in removals" :key="r.id" class="removal-card">
          <div class="r-preview">{{ r.content_preview || '(记忆已不存在)' }}</div>
          <div class="r-meta">
            <span>理由：{{ r.reason }}</span>
            <span>申请人：{{ r.requested_by }}</span>
            <StatusPill :status="r.status" :label="removalLabel(r.status)" />
          </div>
          <div v-if="r.status === 'PENDING'" class="r-actions">
            <button class="btn reject" @click="resolve(r, 'delete')">确认删除</button>
            <button class="btn" @click="resolve(r, 'keep')">保留</button>
          </div>
        </div>
      </div>
    </div>
  </AppShell>
</template>

<script setup>
const api = useApi()
const tab = ref('proposals')
const proposals = ref([])
const removals = ref([])

const tabs = [
  { key: 'proposals', label: '提议审批' },
  { key: 'acl', label: '记忆库与 ACL' },
  { key: 'removal', label: '删除申请' }
]

const pendingCount = computed(() => proposals.value.filter(p => p.status === 'PENDING').length)

async function loadProposals() {
  const data = await api.get('/admin/memory/proposals')
  proposals.value = Array.isArray(data) ? data : []
}

async function loadRemovals() {
  const data = await api.get('/admin/memory/removal-requests')
  removals.value = Array.isArray(data) ? data : []
}

function switchTab(key) {
  tab.value = key
  if (key === 'proposals') loadProposals()
  if (key === 'removal') loadRemovals()
}

function removalLabel(s) {
  return { PENDING: '待处理', DELETED: '已删除', KEPT: '已保留' }[s] || s
}

async function resolve(r, action) {
  try {
    await api.post('/admin/memory/removal-requests/' + r.id + '/resolve', { action })
    await loadRemovals()
  } catch (e) { alert(e.message) }
}

onMounted(loadProposals)
</script>

<style scoped>
.mem-page { padding: 16px 18px; min-height: calc(100vh - 116px); }
.page-tabs { display: flex; gap: 6px; margin-bottom: 16px; }
.tab-btn {
  border: none; background: transparent; padding: 7px 14px; border-radius: 8px;
  font-size: 13px; color: var(--text-2); cursor: pointer; display: inline-flex; align-items: center; gap: 6px;
}
.tab-btn.active { background: var(--primary-light); color: var(--primary); font-weight: 600; }
.badge { background: var(--danger); color: #fff; border-radius: 10px; font-size: 10px; padding: 1px 6px; }
.empty { text-align: center; color: var(--text-3); padding: 40px 0; }
.removal-card {
  border: 1px solid #F0F0F0; border-radius: 12px; padding: 12px 14px; margin-bottom: 10px;
  background: #FFFFFF;
}
.r-preview { font-size: 13px; line-height: 1.7; }
.r-meta {
  display: flex; gap: 12px; align-items: center; flex-wrap: wrap;
  margin-top: 8px; font-size: 11px; color: var(--text-3);
}
.r-actions { margin-top: 10px; display: flex; gap: 8px; }
.r-actions .btn { padding: 4px 14px; font-size: 12px; }
.btn.reject { color: var(--danger); }
.btn.reject:hover { background: rgba(239,68,68,.08); }
</style>

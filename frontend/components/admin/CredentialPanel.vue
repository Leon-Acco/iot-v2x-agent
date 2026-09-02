<template>
  <!-- A5 凭证管理：下发/轮换/吊销，secret 仅展示一次 -->
  <div class="cred-panel">
    <div class="cred-head">
      <span class="cred-title">凭证</span>
      <button class="btn issue-btn" :disabled="loading" @click="issue">+ 下发新凭证</button>
    </div>

    <div v-if="oneTime" class="one-time">
      <div class="warn">secret 仅展示一次，请立即保存</div>
      <div class="kv"><span>client_id</span><code>{{ oneTime.clientId }}</code></div>
      <div class="kv"><span>secret</span><code>{{ oneTime.secret }}</code></div>
      <button class="btn btn-primary" @click="copySecret">复制</button>
    </div>

    <div v-if="!creds.length" class="empty">暂无凭证</div>
    <div v-for="c in creds" :key="c.id" class="cred-row">
      <code class="mono">{{ c.client_id }}</code>
      <StatusPill :status="c.status" />
      <span class="time">{{ fmt(c.created_at) }}</span>
      <span class="ops" v-if="c.status === 'ACTIVE'">
        <button class="btn op" @click="rotate(c)">轮换</button>
        <button class="btn op danger" @click="revoke(c)">吊销</button>
      </span>
    </div>
    <div v-if="error" class="err">{{ error }}</div>
  </div>
</template>

<script setup>
const props = defineProps({
  agentId: { type: [Number, String], required: true }
})
const api = useApi()
const creds = ref([])
const oneTime = ref(null)
const loading = ref(false)
const error = ref('')

async function load() {
  const data = await api.get('/admin/a2a/agents/' + props.agentId + '/credentials')
  creds.value = Array.isArray(data) ? data : []
}

async function issue() {
  loading.value = true
  error.value = ''
  try {
    oneTime.value = await api.post('/admin/a2a/agents/' + props.agentId + '/credentials')
    await load()
  } catch (e) { error.value = e.message } finally { loading.value = false }
}

async function rotate(c) {
  error.value = ''
  try {
    oneTime.value = await api.post('/admin/a2a/credentials/' + c.id + '/rotate')
    await load()
  } catch (e) { error.value = e.message }
}

async function revoke(c) {
  error.value = ''
  try {
    await api.post('/admin/a2a/credentials/' + c.id + '/revoke')
    await load()
  } catch (e) { error.value = e.message }
}

function copySecret() {
  if (!oneTime.value) return
  navigator.clipboard.writeText(oneTime.value.clientId + String.fromCharCode(10) + oneTime.value.secret).catch(() => {})
}

function fmt(t) {
  if (!t) return '-'
  const d = new Date(t)
  return isNaN(d.getTime()) ? String(t) : d.toLocaleString('zh-CN', { hour12: false })
}

onMounted(load)
</script>

<style scoped>
.cred-panel { margin-top: 10px; border-top: 1px dashed #E5E5E5; padding-top: 10px; }
.cred-head { display: flex; align-items: center; justify-content: space-between; margin-bottom: 8px; }
.cred-title { font-size: 12px; font-weight: 700; color: var(--text-2); }
.issue-btn { padding: 4px 10px; font-size: 12px; color: var(--primary); }
.one-time {
  background: #FFF8E8; border: 1px solid rgba(245,158,11,.35); border-radius: 10px;
  padding: 12px; margin-bottom: 10px;
}
.one-time .warn { color: #B45309; font-size: 12px; font-weight: 600; margin-bottom: 8px; }
.kv { display: flex; gap: 8px; font-size: 12px; margin-bottom: 4px; align-items: center; }
.kv span { color: var(--text-3); width: 70px; flex-shrink: 0; }
.kv code { background: #fff; padding: 2px 8px; border-radius: 4px; word-break: break-all; }
.one-time .btn { margin-top: 6px; padding: 4px 14px; font-size: 12px; }
.empty { color: var(--text-3); font-size: 12px; padding: 8px 0; }
.cred-row { display: flex; align-items: center; gap: 10px; padding: 6px 0; font-size: 12px; }
.mono { font-family: "SF Mono", Consolas, monospace; color: var(--text-2); }
.time { color: var(--text-3); font-size: 11px; }
.ops { margin-left: auto; }
.op { padding: 2px 8px; font-size: 12px; }
.op.danger { color: var(--danger); }
.op.danger:hover { background: rgba(239,68,68,.08); color: var(--danger); }
.err { color: var(--danger); font-size: 12px; margin-top: 6px; }
</style>

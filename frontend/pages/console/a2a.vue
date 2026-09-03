<template>
  <!-- A5 A2A 授权审批台：注册审批 + 已授权 Agent 凭证管理 -->
  <AppShell title="A2A 授权审批台">
    <div class="a2a-page panel-card">
      <div class="page-tabs">
        <button class="tab-btn" :class="{ active: tab === 'pending' }" @click="tab = 'pending'">
          注册审批<span v-if="pendingCount" class="badge">{{ pendingCount }}</span>
        </button>
        <button class="tab-btn" :class="{ active: tab === 'approved' }" @click="tab = 'approved'">已授权 Agent</button>
        <button class="btn btn-primary create-btn" @click="createOpen = true">+ 新建注册申请</button>
      </div>

      <div v-if="tab === 'pending'" class="tab-body">
        <div v-if="!pending.length" class="empty">暂无待审批申请</div>
        <div v-for="a in pending" :key="a.id" class="agent-card">
          <div class="agent-head">
            <span class="agent-name">{{ a.agent_name }}</span>
            <StatusPill :status="a.status" />
          </div>
          <div class="agent-desc">{{ a.description || '-' }}</div>
          <div class="agent-meta">
            <span>申请 scope：{{ scopeText(a.requested_scopes) }}</span>
            <span>{{ fmt(a.created_at) }}</span>
          </div>
          <div class="approve-box">
            <input v-model="forms[a.id].roles" placeholder="批准角色（逗号分隔）如 external_agent" />
            <input v-model="forms[a.id].scopes" placeholder="批准 scope 白名单（逗号分隔）" />
            <input v-model="forms[a.id].note" placeholder="审批意见（可选）" />
            <div class="approve-actions">
              <button class="btn btn-primary" @click="approve(a)">通过</button>
              <button class="btn reject" @click="reject(a)">驳回</button>
            </div>
          </div>
        </div>
      </div>

      <div v-if="tab === 'approved'" class="tab-body">
        <div v-if="!approved.length" class="empty">暂无已授权 Agent</div>
        <div v-for="a in approved" :key="a.id" class="agent-card">
          <div class="agent-head">
            <span class="agent-name">{{ a.agent_name }}</span>
            <StatusPill :status="a.status" />
          </div>
          <div class="agent-meta">
            <span>roles：{{ scopeText(a.granted_roles) }}</span>
            <span>scopes：{{ scopeText(a.granted_scopes) }}</span>
          </div>
          <button class="btn toggle-cred" @click="toggleCred(a.id)">
            {{ openCred === a.id ? '收起凭证' : '凭证管理' }}
          </button>
          <CredentialPanel v-if="openCred === a.id" :agent-id="a.id" />
        </div>
      </div>
    </div>

    <DrawerPanel v-model="createOpen" title="新建注册申请" width="440px">
      <label class="form-field"><span>Agent 名称 *</span><input v-model.trim="createForm.agentName" /></label>
      <label class="form-field"><span>描述</span><input v-model.trim="createForm.description" /></label>
      <label class="form-field"><span>回调地址</span><input v-model.trim="createForm.callbackUrl" placeholder="https://" /></label>
      <label class="form-field"><span>申请 scope（逗号分隔）</span><input v-model.trim="createForm.scopes" placeholder="vehicle.alarm.read" /></label>
      <div v-if="createError" class="form-error">{{ createError }}</div>
      <template #footer>
        <button class="btn" @click="createOpen = false">取消</button>
        <button class="btn btn-primary" @click="createAgent">提交</button>
      </template>
    </DrawerPanel>
  </AppShell>
</template>

<script setup>
const api = useApi()
const tab = ref('pending')
const agents = ref([])
const openCred = ref(null)
const forms = ref({})
const createOpen = ref(false)
const createForm = ref({ agentName: '', description: '', callbackUrl: '', scopes: '' })
const createError = ref('')

const pending = computed(() => agents.value.filter(a => a.status === 'PENDING'))
const approved = computed(() => agents.value.filter(a => a.status === 'APPROVED'))
const pendingCount = computed(() => pending.value.length)

function scopeText(raw) {
  if (!raw) return '-'
  try {
    const arr = typeof raw === 'string' ? JSON.parse(raw) : raw
    return Array.isArray(arr) && arr.length ? arr.join(', ') : '-'
  } catch (e) { return String(raw) }
}

function fmt(t) {
  if (!t) return '-'
  const d = new Date(t)
  return isNaN(d.getTime()) ? String(t) : d.toLocaleString('zh-CN', { hour12: false })
}

function ensureForm(id) {
  if (!forms.value[id]) forms.value[id] = { roles: '', scopes: '', note: '' }
  return forms.value[id]
}

function splitCsv(s) {
  return (s || '').split(',').map(x => x.trim()).filter(Boolean)
}

async function load() {
  const data = await api.get('/admin/a2a/agents')
  agents.value = Array.isArray(data) ? data : []
  agents.value.forEach(a => ensureForm(a.id))
}

async function approve(a) {
  const f = ensureForm(a.id)
  try {
    await api.post('/admin/a2a/agents/' + a.id + '/approve', {
      roles: splitCsv(f.roles), scopes: splitCsv(f.scopes), note: f.note
    })
    await load()
  } catch (e) { alert(e.message) }
}

async function reject(a) {
  const reason = window.prompt('驳回理由（必填）')
  if (!reason) return
  try {
    await api.post('/admin/a2a/agents/' + a.id + '/reject', { reason })
    await load()
  } catch (e) { alert(e.message) }
}

async function createAgent() {
  createError.value = ''
  if (!createForm.value.agentName) {
    createError.value = 'Agent 名称必填'
    return
  }
  try {
    await api.post('/admin/a2a/agents', {
      agentName: createForm.value.agentName,
      description: createForm.value.description,
      callbackUrl: createForm.value.callbackUrl || null,
      requestedScopes: splitCsv(createForm.value.scopes)
    })
    createOpen.value = false
    createForm.value = { agentName: '', description: '', callbackUrl: '', scopes: '' }
    await load()
  } catch (e) { createError.value = e.message }
}

function toggleCred(id) {
  openCred.value = openCred.value === id ? null : id
}

onMounted(load)
</script>

<style scoped>
.a2a-page { padding: 16px 18px; min-height: calc(100vh - 116px); display: flex; flex-direction: column; }
.page-tabs { display: flex; align-items: center; gap: 6px; margin-bottom: 16px; }
.tab-btn {
  border: none; background: transparent; padding: 7px 14px; border-radius: 8px;
  font-size: 13px; color: var(--text-2); cursor: pointer; display: inline-flex; align-items: center; gap: 6px;
}
.tab-btn.active { background: var(--primary-light); color: var(--primary); font-weight: 600; }
.badge {
  background: var(--danger); color: #fff; border-radius: 10px;
  font-size: 10px; padding: 1px 6px; line-height: 1.4;
}
.create-btn { margin-left: auto; }
.tab-body { flex: 1; display: flex; flex-direction: column; }
.empty { text-align: center; color: var(--text-3); padding: 40px 0; font-size: 13px; margin: auto; }
.agent-card {
  border: 1px solid #F0F0F0; border-radius: 12px; padding: 14px 16px; margin-bottom: 12px;
  background: #FFFFFF;
}
.agent-head { display: flex; align-items: center; gap: 10px; }
.agent-name { font-size: 14px; font-weight: 700; }
.agent-desc { font-size: 12px; color: var(--text-2); margin-top: 4px; }
.agent-meta {
  display: flex; gap: 16px; flex-wrap: wrap; margin-top: 6px;
  font-size: 12px; color: var(--text-3);
}
.approve-box { margin-top: 10px; display: flex; flex-direction: column; gap: 8px; }
.approve-box input {
  padding: 8px 10px; font-size: 12px; border: 1px solid #E5E5E5;
  border-radius: 8px; background: #fff; outline: none;
}
.approve-box input:focus { border-color: var(--primary); }
.approve-actions { display: flex; gap: 8px; }
.btn.reject { color: var(--danger); height: 36px; padding: 0 18px; font-size: 13px; }
.btn.reject:hover { background: rgba(239,68,68,.08); color: var(--danger); }
.toggle-cred { margin-top: 8px; padding: 4px 10px; font-size: 12px; }
.form-field { display: block; margin-bottom: 12px; }
.form-field span { display: block; font-size: 12px; color: var(--text-2); margin-bottom: 4px; }
.form-field input {
  width: 100%; padding: 8px 10px; font-size: 13px;
  border: 1px solid #E5E5E5; border-radius: 8px; outline: none; background: #F3F3F5;
}
.form-error { color: var(--danger); font-size: 12px; }
</style>

<template>
  <!-- Capability 管理台：统计卡 + 能力列表 / AI 生成 / 审批中心 三 Tab -->
  <AppShell title="Capability 管理台">
    <div class="cap-page">
      <div class="stat-row">
        <StatCard :label="'全部能力'" :value="items.length" />
        <StatCard :label="'已上线'" :value="onlineCount" accent="var(--primary)" />
        <StatCard :label="'草稿'" :value="draftCount" accent="#b7791f" />
        <StatCard :label="'待审批'" :value="pendingCount" accent="#4F46E5" />
      </div>

      <div class="tab-bar">
        <div class="tab-group">
          <button
            v-for="t in tabs" :key="t.key"
            class="tab-item" :class="{ active: tab === t.key }"
            @click="tab = t.key"
          >
            {{ t.label }}
            <span v-if="t.key === 'approval' && pendingCount" class="tab-badge">{{ pendingCount }}</span>
          </button>
        </div>
        <div v-if="tab === 'list'" class="tab-actions">
          <button class="btn ai-btn" @click="tab = 'ai'">✨ AI 生成</button>
          <button class="btn btn-primary" @click="openCreate">+ 新建能力</button>
        </div>
      </div>

      <div v-if="tab === 'list'" class="panel-card list-panel">
        <div class="toolbar">
          <div class="search-box">
            <svg viewBox="0 0 24 24" width="14" height="14" fill="none" stroke="currentColor" stroke-width="2" stroke-linecap="round"><circle cx="11" cy="11" r="7"/><path d="m20 20-3.5-3.5"/></svg>
            <input v-model.trim="keyword" class="search-input" :placeholder="'搜索 ID / 显示名 / 别名'" />
          </div>
          <select v-model="statusFilter" class="filter-select">
            <option value="">全部状态</option>
            <option value="online">已上线</option>
            <option value="staging">预发</option>
            <option value="draft">草稿</option>
            <option value="deprecated">已下线</option>
          </select>
          <select v-model="domainFilter" class="filter-select">
            <option value="">全部域</option>
            <option v-for="d in domains" :key="d" :value="d">{{ d }}</option>
          </select>
          <span class="total">共 {{ filtered.length }} 个</span>
        </div>
        <div v-if="loadError" class="load-error">{{ loadError }}</div>
        <CapabilityTable
          v-else
          :items="filtered"
          :lint-map="lintMap"
          @detail="openDetail"
          @edit="openEdit"
          @dryrun="openDryRun"
          @status="askStatus"
          @history="openHistory"
        />
      </div>

      <AiGeneratePanel
        v-if="tab === 'ai'"
        @saved="onAiSaved"
        @edit-draft="openEditDraft"
        @dryrun="openDryRunById"
      />

      <div v-if="tab === 'approval'" class="panel-card list-panel">
        <CapabilityProposalList ref="proposalRef" @changed="loadAll" @dryrun="openDryRunById" />
      </div>
    </div>

    <DrawerPanel v-model="editorOpen" :title="editorTitle" width="680px">
      <CapabilityEditor ref="editorRef" :original="editing" :preset="preset" @save="onSave" />
      <template #footer>
        <button class="btn" @click="editorOpen = false">取消</button>
        <button class="btn btn-primary" :disabled="saving" @click="triggerSave">{{ saving ? '保存中…' : '保存' }}</button>
      </template>
    </DrawerPanel>

    <DrawerPanel v-model="detailOpen" :title="'能力详情'" width="640px">
      <CapabilityDetailPanel
        v-if="detailCap"
        :capability="detailCap"
        :lint="lintMap[detailCap.id] || null"
        :key="detailCap.id"
        @dryrun="openDryRun"
        @edit="openEdit"
        @history="openHistory"
      />
    </DrawerPanel>

    <DrawerPanel v-model="dryRunOpen" :title="'在线试跑：' + (dryRunning || '')" width="640px">
      <DryRunPanel v-if="dryRunning" :capability-id="dryRunning" :key="dryRunning" />
    </DrawerPanel>

    <DrawerPanel v-model="historyOpen" :title="'版本历史：' + (historyCap || '')" width="640px">
      <VersionHistoryPanel v-if="historyCap" :capability-id="historyCap" :key="historyCap" />
    </DrawerPanel>

    <ConfirmDialog
      v-model="confirmOpen"
      :title="confirmTitle"
      :message="confirmMsg"
      :danger="pendingStatus === 'deprecated'"
      @confirm="doStatus"
      @cancel="confirmOpen = false"
    />
  </AppShell>
</template>

<script setup>
const api = useApi()
const items = ref([])
const lintMap = ref({})
const proposals = ref([])
const loadError = ref('')
const keyword = ref('')
const statusFilter = ref('')
const domainFilter = ref('')
const tab = ref('list')
const tabs = [
  { key: 'list', label: '能力列表' },
  { key: 'ai', label: 'AI 生成' },
  { key: 'approval', label: '审批中心' }
]

const editorOpen = ref(false)
const editing = ref(null)
const preset = ref(null)
const editorRef = ref(null)
const proposalRef = ref(null)
const saving = ref(false)

const detailOpen = ref(false)
const detailCap = ref(null)

const dryRunOpen = ref(false)
const dryRunning = ref('')

const historyOpen = ref(false)
const historyCap = ref('')

const confirmOpen = ref(false)
const pendingCap = ref(null)
const pendingStatus = ref('')

const onlineCount = computed(() => items.value.filter(c => c.status === 'online').length)
const draftCount = computed(() => items.value.filter(c => c.status === 'draft').length)
const pendingCount = computed(() => proposals.value.filter(p => p.status === 'PENDING').length)

const domains = computed(() => [...new Set(items.value.map(c => c.domain).filter(Boolean))].sort())

const editorTitle = computed(() => {
  if (editing.value) return '编辑能力：' + editing.value.id
  return preset.value ? 'AI 草稿调整' : '新建能力'
})

const filtered = computed(() => {
  const kw = keyword.value.toLowerCase()
  return items.value.filter(c => {
    if (statusFilter.value && c.status !== statusFilter.value) return false
    if (domainFilter.value && c.domain !== domainFilter.value) return false
    if (!kw) return true
    const hay = [c.id, c.display, (c.aliases || []).join(' ')].join(' ').toLowerCase()
    return hay.includes(kw)
  })
})

const confirmTitle = computed(() => pendingStatus.value === 'online' ? '确认上线' : '确认下线')
const confirmMsg = computed(() => {
  if (!pendingCap.value) return ''
  return pendingStatus.value === 'online'
    ? '上线后「' + pendingCap.value.id + '」对外可调用（若无已通过的审批单，将自动进入审批流程）'
    : '下线后「' + pendingCap.value.id + '」调用将被拒绝'
})

async function load() {
  loadError.value = ''
  try {
    const data = await api.get('/admin/capabilities')
    items.value = Array.isArray(data) ? data : []
  } catch (e) {
    loadError.value = e.message || '加载失败'
  }
}

// 加载完整度评分（合并为 id → lint 映射）
async function loadLint() {
  try {
    const data = await api.get('/admin/capabilities-lint')
    const map = {}
    for (const l of (Array.isArray(data) ? data : [])) map[l.id] = l
    lintMap.value = map
  } catch (e) { /* lint 仅作辅助展示 */ }
}

async function loadProposals() {
  try {
    const data = await api.get('/admin/capability-proposals')
    proposals.value = Array.isArray(data) ? data : []
  } catch (e) { /* 审批数量仅作徽标，不拦截主流程 */ }
}

function loadAll() {
  load()
  loadLint()
  loadProposals()
}

function openCreate() {
  editing.value = null
  preset.value = null
  editorOpen.value = true
}

function openEdit(c) {
  editing.value = c
  preset.value = null
  editorOpen.value = true
}

// AI 草稿跳转编辑器调整（id 可改，不算编辑已有能力）
function openEditDraft(def) {
  editing.value = null
  preset.value = def
  editorOpen.value = true
}

function openDetail(c) {
  detailCap.value = c
  detailOpen.value = true
}

function openDryRun(c) {
  dryRunning.value = c.id
  dryRunOpen.value = true
}

function openDryRunById(id) {
  dryRunning.value = id
  dryRunOpen.value = true
}

function openHistory(c) {
  historyCap.value = c.id
  historyOpen.value = true
}

function triggerSave() {
  if (editorRef.value) editorRef.value.submit()
}

async function onSave(form) {
  saving.value = true
  try {
    if (editing.value) {
      await api.put('/admin/capabilities/' + encodeURIComponent(editing.value.id), form)
    } else {
      await api.post('/admin/capabilities', form)
    }
    editorOpen.value = false
    preset.value = null
    await loadAll()
  } catch (e) {
    alert(e.message || '保存失败')
  } finally {
    saving.value = false
  }
}

function onAiSaved() {
  tab.value = 'approval'
  loadAll()
}

function askStatus(c, status) {
  pendingCap.value = c
  pendingStatus.value = status
  confirmOpen.value = true
}

async function doStatus() {
  confirmOpen.value = false
  if (!pendingCap.value) return
  try {
    const res = await api.post('/admin/capabilities/' + encodeURIComponent(pendingCap.value.id) + '/status', { status: pendingStatus.value })
    if (res && res.needApproval) {
      alert(res.message || '已提交审批')
      tab.value = 'approval'
    }
    await loadAll()
  } catch (e) {
    alert(e.message)
  }
}

onMounted(loadAll)
</script>

<style scoped>
.cap-page { padding: 16px 18px; min-height: calc(100vh - 116px); }
.stat-row { display: grid; grid-template-columns: repeat(4, 1fr); gap: 14px; margin-bottom: 16px; }

.tab-bar {
  display: flex; align-items: center; justify-content: space-between;
  margin-bottom: 14px;
}
.tab-group {
  display: inline-flex; gap: 4px; padding: 4px;
  background: #EDF3F0; border-radius: 10px;
}
.tab-item {
  position: relative; padding: 7px 18px; font-size: 13px; border: none; cursor: pointer;
  background: transparent; color: var(--text-2); border-radius: 8px;
  transition: all .18s ease; font-weight: 500;
}
.tab-item:hover { color: var(--primary); }
.tab-item.active { background: #fff; color: var(--primary); box-shadow: 0 2px 8px rgba(15, 138, 106, .12); }
.tab-badge {
  display: inline-block; min-width: 17px; height: 17px; line-height: 17px; margin-left: 6px;
  background: #4F46E5; color: #fff; font-size: 11px; border-radius: 9px; text-align: center; padding: 0 4px;
}
.tab-actions { display: flex; gap: 10px; }
.ai-btn { color: #4F46E5; border-color: #c7d2fe; background: #eef2ff; }
.ai-btn:hover { background: #e0e7ff; border-color: #818cf8; color: #4338ca; }

.list-panel { padding: 16px 18px; }
.toolbar { display: flex; align-items: center; gap: 10px; margin-bottom: 14px; }
.search-box {
  display: flex; align-items: center; gap: 6px; padding: 0 12px;
  border: 1px solid #E5E5E5; border-radius: 8px; background: #F3F3F5; color: var(--text-3);
}
.search-box:focus-within { border-color: var(--primary); color: var(--primary); }
.search-input {
  width: 230px; padding: 8px 0; font-size: 13px;
  border: none; outline: none; background: transparent;
}
.filter-select {
  padding: 8px 10px; font-size: 13px; border: 1px solid #E5E5E5;
  border-radius: 8px; background: #F3F3F5; outline: none; color: var(--text-2);
}
.total { font-size: 12px; color: var(--text-3); margin-left: auto; }
.load-error { color: var(--danger); padding: 30px; text-align: center; }

@media (max-width: 1100px) {
  .stat-row { grid-template-columns: repeat(2, 1fr); }
}
</style>

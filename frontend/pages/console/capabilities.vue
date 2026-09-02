<template>
  <!-- P4 Capability 管理后台：列表 + 编辑抽屉 + 在线试跑 + 上下线 -->
  <AppShell title="Capability 管理">
    <div class="cap-page panel-card">
      <div class="toolbar">
        <input v-model.trim="keyword" class="search-input" placeholder="搜索 ID / 显示名 / 别名" />
        <select v-model="statusFilter" class="status-filter">
          <option value="">全部状态</option>
          <option value="online">已上线</option>
          <option value="staging">预发</option>
          <option value="draft">草稿</option>
          <option value="deprecated">已下线</option>
        </select>
        <span class="total">共 {{ filtered.length }} 个</span>
        <button class="btn btn-primary create-btn" @click="openCreate">+ 新建能力</button>
      </div>

      <div v-if="loadError" class="load-error">{{ loadError }}</div>
      <CapabilityTable
        v-else
        :items="filtered"
        @edit="openEdit"
        @dryrun="openDryRun"
        @status="askStatus"
      />
    </div>

    <DrawerPanel v-model="editorOpen" :title="editing ? '编辑能力：' + editing.id : '新建能力'" width="560px">
      <CapabilityEditor ref="editorRef" :original="editing" @save="onSave" />
      <template #footer>
        <button class="btn" @click="editorOpen = false">取消</button>
        <button class="btn btn-primary" :disabled="saving" @click="triggerSave">{{ saving ? '保存中…' : '保存' }}</button>
      </template>
    </DrawerPanel>

    <DrawerPanel v-model="dryRunOpen" :title="'在线试跑：' + (dryRunning ? dryRunning.id : '')" width="620px">
      <DryRunPanel v-if="dryRunning" :capability-id="dryRunning.id" :key="dryRunning.id" />
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
const loadError = ref('')
const keyword = ref('')
const statusFilter = ref('')

const editorOpen = ref(false)
const editing = ref(null)
const editorRef = ref(null)
const saving = ref(false)

const dryRunOpen = ref(false)
const dryRunning = ref(null)

const confirmOpen = ref(false)
const pendingCap = ref(null)
const pendingStatus = ref('')

const filtered = computed(() => {
  const kw = keyword.value.toLowerCase()
  return items.value.filter(c => {
    if (statusFilter.value && c.status !== statusFilter.value) return false
    if (!kw) return true
    const hay = [c.id, c.display, (c.aliases || []).join(' ')].join(' ').toLowerCase()
    return hay.includes(kw)
  })
})

const confirmTitle = computed(() => pendingStatus.value === 'online' ? '确认上线' : '确认下线')
const confirmMsg = computed(() => {
  if (!pendingCap.value) return ''
  return pendingStatus.value === 'online'
    ? '上线后「' + pendingCap.value.id + '」对外可调用'
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

function openCreate() {
  editing.value = null
  editorOpen.value = true
}

function openEdit(c) {
  editing.value = c
  editorOpen.value = true
}

function openDryRun(c) {
  dryRunning.value = c
  dryRunOpen.value = true
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
    await load()
  } catch (e) {
    alert(e.message || '保存失败')
  } finally {
    saving.value = false
  }
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
    await api.post('/admin/capabilities/' + encodeURIComponent(pendingCap.value.id) + '/status', { status: pendingStatus.value })
    await load()
  } catch (e) {
    alert(e.message)
  }
}

onMounted(load)
</script>

<style scoped>
.cap-page { padding: 16px 18px; min-height: calc(100vh - 116px); }
.toolbar { display: flex; align-items: center; gap: 10px; margin-bottom: 14px; }
.search-input {
  width: 260px; padding: 8px 12px; font-size: 13px;
  border: 1px solid #E5E5E5; border-radius: 8px; outline: none; background: #F3F3F5;
}
.search-input:focus { border-color: var(--primary); }
.status-filter {
  padding: 8px 10px; font-size: 13px; border: 1px solid #E5E5E5;
  border-radius: 8px; background: #F3F3F5; outline: none; color: var(--text-2);
}
.total { font-size: 12px; color: var(--text-3); }
.create-btn { margin-left: auto; }
.load-error { color: var(--danger); padding: 30px; text-align: center; }
</style>

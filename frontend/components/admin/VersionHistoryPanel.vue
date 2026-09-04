<template>
  <!-- 版本历史：左侧版本列表（新到旧），右侧快照预览（只读） -->
  <div class="vh-panel">
    <div v-if="loadError" class="load-error">{{ loadError }}</div>
    <template v-else>
      <div v-if="!versions.length" class="empty">暂无历史版本（更新/上下线后自动生成快照）</div>
      <div v-else class="vh-body">
        <div class="v-list">
          <button
            v-for="v in versions" :key="v.version"
            class="v-item" :class="{ active: current === v.version }"
            @click="loadSnapshot(v.version)"
          >
            <span class="v-no mono">v{{ v.version }}</span>
            <span class="v-status">{{ v.status }}</span>
            <span class="v-time">{{ fmt(v.updated_at) }}</span>
          </button>
        </div>
        <div class="v-detail">
          <div v-if="loadingSnap" class="empty">加载中…</div>
          <template v-else-if="snapshot">
            <div class="snap-head">
              <span class="mono">{{ snapshot.id }}</span>
              <span class="v-status">{{ snapshot.display }}</span>
            </div>
            <div class="field-label">SQL 模板</div>
            <pre class="sql-view">{{ snapshot.sqlTemplate || '（编排引用型无 SQL）' }}</pre>
            <div class="field-label">完整定义</div>
            <pre class="json-view">{{ snapshotJson }}</pre>
          </template>
          <div v-else class="empty">选择左侧版本查看快照</div>
        </div>
      </div>
    </template>
  </div>
</template>

<script setup>
const props = defineProps({
  capabilityId: { type: String, required: true }
})
const api = useApi()

const versions = ref([])
const loadError = ref('')
const current = ref(null)
const snapshot = ref(null)
const loadingSnap = ref(false)

const snapshotJson = computed(() => snapshot.value ? JSON.stringify(snapshot.value, null, 2) : '')

function fmt(t) {
  if (!t) return '-'
  const d = new Date(t)
  return isNaN(d.getTime()) ? String(t) : d.toLocaleString('zh-CN', { hour12: false })
}

async function load() {
  loadError.value = ''
  try {
    const data = await api.get('/admin/capabilities/' + encodeURIComponent(props.capabilityId) + '/versions')
    versions.value = Array.isArray(data) ? data : []
    if (versions.value.length) loadSnapshot(versions.value[0].version)
  } catch (e) {
    loadError.value = e.message || '加载失败'
  }
}

async function loadSnapshot(v) {
  current.value = v
  loadingSnap.value = true
  try {
    snapshot.value = await api.get('/admin/capabilities/' + encodeURIComponent(props.capabilityId) + '/versions/' + v)
  } catch (e) {
    snapshot.value = null
  } finally {
    loadingSnap.value = false
  }
}

onMounted(load)
</script>

<style scoped>
.vh-body { display: grid; grid-template-columns: 200px 1fr; gap: 14px; }
.load-error { color: var(--danger); padding: 30px; text-align: center; }
.empty { text-align: center; color: var(--text-3); padding: 36px 0; font-size: 13px; }
.v-list { display: flex; flex-direction: column; gap: 6px; max-height: 420px; overflow-y: auto; }
.v-item {
  display: flex; flex-direction: column; gap: 2px; text-align: left;
  padding: 8px 10px; border: 1px solid #EEF2F0; border-radius: 8px;
  background: #fff; cursor: pointer; transition: all .15s ease;
}
.v-item:hover { border-color: var(--primary-600); }
.v-item.active { border-color: var(--primary); background: var(--primary-light); }
.v-no { font-weight: 600; font-size: 13px; }
.mono { font-family: "SF Mono", Consolas, monospace; }
.v-status { font-size: 11px; color: var(--text-2); }
.v-time { font-size: 11px; color: var(--text-3); }
.snap-head { display: flex; gap: 10px; align-items: center; margin-bottom: 10px; font-size: 14px; font-weight: 600; }
.field-label { font-size: 12px; color: var(--text-3); margin: 10px 0 6px; }
.sql-view, .json-view {
  background: #0F1B2D; color: #d7e4dd; font-family: "SF Mono", Consolas, monospace;
  font-size: 12px; line-height: 1.7; padding: 12px 14px; border-radius: 8px;
  overflow-x: auto; white-space: pre-wrap; word-break: break-all;
  max-height: 260px; overflow-y: auto;
}
</style>

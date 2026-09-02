<template>
  <!-- 在线试跑：参数 JSON -> 真实 SQL / 行数 / 耗时 / 前 20 行 -->
  <div class="dry-run">
    <div class="field-label">参数（JSON）</div>
    <textarea v-model="paramsText" class="params-editor" rows="6" spellcheck="false"></textarea>
    <div v-if="parseError" class="dry-error">{{ parseError }}</div>
    <button class="btn btn-primary run-btn" :disabled="running" @click="doRun">
      {{ running ? '试跑中…' : '试跑' }}
    </button>

    <template v-if="result">
      <div class="result-meta">
        <span class="pill">行数 {{ result.rowCount }}</span>
        <span class="pill">耗时 {{ result.elapsedMs }}ms</span>
        <span class="pill" :class="{ warn: result.truncated }">{{ result.truncated ? '已截断' : '未截断' }}</span>
      </div>
      <div class="field-label">SQL</div>
      <pre class="sql-view">{{ result.sqlSnapshot }}</pre>
      <div class="field-label">前 {{ previewRows.length }} 行</div>
      <DataTable :columns="tableColumns" :rows="previewRows" />
    </template>
  </div>
</template>

<script setup>
const props = defineProps({
  capabilityId: { type: String, required: true }
})
const api = useApi()

const paramsText = ref('{}')
const running = ref(false)
const parseError = ref('')
const result = ref(null)

// 转换为 DataTable 需要的列结构
const tableColumns = computed(() => {
  const cols = result.value && Array.isArray(result.value.columns) ? result.value.columns : []
  return cols.map(c => typeof c === 'string' ? { name: c, display: c } : c)
})
const previewRows = computed(() => {
  const rows = result.value && Array.isArray(result.value.rows) ? result.value.rows : []
  return rows.slice(0, 20)
})

async function doRun() {
  parseError.value = ''
  let params = {}
  try {
    params = paramsText.value.trim() ? JSON.parse(paramsText.value) : {}
  } catch (e) {
    parseError.value = 'JSON 解析失败：' + e.message
    return
  }
  running.value = true
  result.value = null
  try {
    result.value = await api.post('/admin/capabilities/' + encodeURIComponent(props.capabilityId) + '/dry-run', { params })
  } catch (e) {
    parseError.value = e.message
  } finally {
    running.value = false
  }
}
</script>

<style scoped>
.field-label { font-size: 12px; color: var(--text-2); margin: 12px 0 6px; }
.params-editor {
  width: 100%; font-family: "SF Mono", Consolas, monospace; font-size: 12px;
  padding: 10px; border: 1px solid #E5E5E5; border-radius: 8px;
  background: #F3F3F5; outline: none; resize: vertical;
}
.dry-error { color: var(--danger); font-size: 12px; margin-top: 6px; }
.run-btn { margin-top: 10px; }
.result-meta { display: flex; gap: 6px; margin-top: 14px; }
.pill { font-size: 11px; color: var(--text-3); background: #F3F4F6; border-radius: 20px; padding: 3px 10px; }
.pill.warn { color: var(--warning); background: rgba(245,158,11,.1); }
.sql-view {
  background: #111827; color: #E5E7EB; font-size: 11px; padding: 10px;
  border-radius: 8px; white-space: pre-wrap; word-break: break-all;
  max-height: 200px; overflow-y: auto; margin: 0;
}
</style>

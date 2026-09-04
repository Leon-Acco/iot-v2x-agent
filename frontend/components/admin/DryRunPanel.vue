<template>
  <!-- 在线试跑：自动填充真实样例参数（权限内车牌/近7天），开箱有数据 -->
  <div class="dry-run">
    <div class="field-row">
      <div class="field-label">参数（JSON）</div>
      <button class="btn sample-btn" :disabled="sampling" @click="fillSample">
        {{ sampling ? '填充中…' : '重新填充样例' }}
      </button>
    </div>
    <textarea v-model="paramsText" class="params-editor" rows="6" spellcheck="false"></textarea>
    <div v-if="sampleNote" class="sample-note">{{ sampleNote }}</div>
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
const sampling = ref(false)
const sampleNote = ref('')
const parseError = ref('')
const result = ref(null)

// 转换为 DataTable 需要的列结构
const tableColumns = computed(() => {
  const cols = result.value && Array.isArray(result.value.columns) ? result.value.columns : []
  return cols.map(c => typeof c === 'string' ? { name: c, display: c } : c)
})
const previewRows = computed(() => result.value && Array.isArray(result.value.rows) ? result.value.rows : [])

// 填充样例参数：daterange→近N天、车辆→权限内真实车牌/VIN
async function fillSample() {
  sampling.value = true
  sampleNote.value = ''
  try {
    const data = await api.get('/admin/capabilities/' + encodeURIComponent(props.capabilityId) + '/sample-params')
    paramsText.value = JSON.stringify(data || {}, null, 2)
    sampleNote.value = Object.keys(data || {}).length
      ? '已填充权限内真实样例，可直接试跑'
      : '该能力无需参数，可直接试跑'
  } catch (e) {
    sampleNote.value = ''
  } finally {
    sampling.value = false
  }
}

async function doRun() {
  parseError.value = ''
  let params
  try {
    params = JSON.parse(paramsText.value || '{}')
  } catch (e) {
    parseError.value = 'JSON 格式错误：' + e.message
    return
  }
  running.value = true
  try {
    result.value = await api.post('/admin/capabilities/' + encodeURIComponent(props.capabilityId) + '/dry-run', { params })
  } catch (e) {
    parseError.value = e.message || '试跑失败'
  } finally {
    running.value = false
  }
}

onMounted(fillSample)
</script>

<style scoped>
.dry-run { display: flex; flex-direction: column; gap: 10px; }
.field-row { display: flex; align-items: center; justify-content: space-between; }
.field-label { font-size: 12px; color: var(--text-3); }
.sample-btn { font-size: 11px; padding: 3px 10px; }
.sample-note { font-size: 11px; color: var(--primary-deep); }
.params-editor {
  width: 100%; font-family: "SF Mono", Consolas, monospace; font-size: 12px; line-height: 1.6;
  padding: 10px 12px; border: 1px solid #E5E5E5; border-radius: 8px; outline: none;
  background: #F9FAFB; resize: vertical;
}
.params-editor:focus { border-color: var(--primary); }
.dry-error { color: var(--danger); font-size: 12px; }
.run-btn { align-self: flex-start; }
.result-meta { display: flex; gap: 8px; }
.pill { font-size: 11px; padding: 3px 10px; border-radius: 10px; background: var(--primary-light); color: var(--primary-deep); }
.pill.warn { background: #fffbeb; color: #b45309; }
.sql-view {
  background: #0F1B2D; color: #d7e4dd; font-family: "SF Mono", Consolas, monospace;
  font-size: 12px; line-height: 1.7; padding: 12px 14px; border-radius: 8px;
  overflow-x: auto; white-space: pre-wrap; word-break: break-all;
  max-height: 220px; overflow-y: auto;
}
</style>

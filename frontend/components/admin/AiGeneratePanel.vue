<template>
  <!-- AI 生成能力：表清单前置展示（表名/注释/字段），每表一键 AI 生成；也可勾选多表 + 补充描述联合生成 -->
  <div class="ai-panel">
    <div class="panel-card input-card">
      <div class="card-title">
        <span class="spark">✨</span> 数据表清单
        <span class="title-sub">共 {{ tables.length }} 张，点「字段」看字段，点「AI 生成」自动设计能力</span>
      </div>

      <div class="picker-search">
        <input v-model.trim="tableKeyword" :placeholder="'搜索表名 / 注释'" />
      </div>
      <div class="picker-list">
        <div v-for="t in filteredTables" :key="t.table_name" class="picker-block" :class="{ checked: selected.includes(t.table_name) }">
          <div class="picker-item">
            <input type="checkbox" :checked="selected.includes(t.table_name)" @change="toggleTable(t.table_name)" />
            <span class="t-name">{{ t.table_name }}</span>
            <button class="detail-btn" @click.stop="toggleDetail(t.table_name)">
              {{ expanded[t.table_name] ? '收起' : '字段' }}
            </button>
            <button class="btn gen-one" :disabled="generating" @click.stop="generateOne(t.table_name)">
              <span v-if="generating && selected[0] === t.table_name" class="btn-spin"></span>
              ✨ AI 生成
            </button>
          </div>
          <div class="t-comment" v-if="t.table_comment">{{ t.table_comment }}</div>
          <div v-if="expanded[t.table_name]" class="col-detail">
            <div v-if="!columnsMap[t.table_name]" class="col-loading">加载中…</div>
            <template v-else>
              <div v-for="c in columnsMap[t.table_name]" :key="c.column_name" class="col-row">
                <span class="c-name mono">{{ c.column_name }}</span>
                <span class="c-type">{{ c.column_type }}</span>
                <span class="c-comment">{{ c.column_comment }}</span>
              </div>
            </template>
          </div>
        </div>
        <div v-if="!tables.length" class="picker-empty">{{ tablesError || '加载中…' }}</div>
      </div>

      <div class="field-label">补充需求（可选；留空则 AI 自主分析表结构设计能力）</div>
      <div class="tpl-row">
        <button v-for="tp in templates" :key="tp" class="tpl-chip" @click="description = tp">{{ tp.slice(0, 14) }}…</button>
      </div>
      <textarea v-model="description" rows="3" class="desc-input"
        :placeholder="'例如：按车队汇总对比近7天的总量，出柱状图'"></textarea>

      <div v-if="error" class="gen-error">{{ error }}</div>
      <button class="btn btn-primary gen-btn" :disabled="generating || !selected.length" @click="generate">
        <span v-if="generating" class="btn-spin"></span>
        {{ generating ? '生成中…' : '✨ 基于勾选 ' + selected.length + ' 张表生成' }}
      </button>
    </div>

    <div class="panel-card preview-card">
      <div v-if="generating" class="gen-loading">
        <div class="spin-ring"></div>
        <div class="loading-title">AI 正在分析表结构并设计能力…</div>
        <div class="loading-sub">生成完整能力定义约需 30~60 秒，请稍候</div>
      </div>
      <template v-else-if="!result">
        <div class="preview-empty">
          <div class="empty-icon">✨</div>
          <div class="empty-title">生成预览</div>
          <div class="empty-desc">点表后的「AI 生成」，AI 会基于该表字段与注释分析能做什么能力；<br/>也可勾选多表 + 补充需求联合生成</div>
        </div>
      </template>
      <template v-else>
        <div v-if="result.mock" class="mock-banner">{{ result.notice }}</div>
        <div class="result-head">
          <div>
            <div class="r-id mono">{{ def.id }}</div>
            <div class="r-name">{{ def.display }}</div>
          </div>
          <span class="valid-pill">校验通过</span>
        </div>
        <p class="r-desc">{{ def.description }}</p>
        <div class="r-grid">
          <div class="r-item"><span>域</span>{{ def.domain }}</div>
          <div class="r-item"><span>图表</span>{{ def.chartHint }}</div>
          <div class="r-item"><span>参数</span>{{ (def.params || []).length }} 个</div>
          <div class="r-item"><span>返回列</span>{{ (def.returns && def.returns.columns || []).length }} 列</div>
        </div>
        <div class="field-label">SQL 模板</div>
        <pre class="sql-view">{{ def.sqlTemplate }}</pre>
        <div v-if="saveMsg" class="save-ok">{{ saveMsg }}</div>
        <div class="r-actions">
          <button class="btn" :disabled="generating" @click="generate">重新生成</button>
          <button class="btn" @click="$emit('edit-draft', def)">在编辑器中调整</button>
          <button class="btn btn-primary" :disabled="saving || savedFlag" @click="save">
            {{ savedFlag ? '已提交审批' : (saving ? '保存中…' : '保存草稿并提交审批') }}
          </button>
        </div>
      </template>
    </div>
  </div>
</template>

<script setup>
const emit = defineEmits(['saved', 'edit-draft', 'dryrun'])
const api = useApi()

const description = ref('')
const tables = ref([])
const tablesError = ref('')
const tableKeyword = ref('')
const selected = ref([])
const expanded = ref({})
const columnsMap = ref({})
const templates = [
  '按天统计近30天的总量趋势，按日期出折线图',
  '按车队汇总对比近7天的总量，出柱状图',
  '查询指定车辆近7天的明细清单，按时间倒序'
]

const generating = ref(false)
const result = ref(null)
const error = ref('')
const saving = ref(false)
const savedFlag = ref(false)
const saveMsg = ref('')

const def = computed(() => result.value ? result.value.definition : null)

const filteredTables = computed(() => {
  const kw = tableKeyword.value.toLowerCase()
  if (!kw) return tables.value
  return tables.value.filter(t =>
    (t.table_name || '').toLowerCase().includes(kw) ||
    (t.table_comment || '').toLowerCase().includes(kw))
})

function toggleTable(name) {
  const i = selected.value.indexOf(name)
  if (i >= 0) selected.value.splice(i, 1)
  else selected.value.push(name)
}

// 展开/收起表字段（首次展开时拉取列信息，组件内缓存）
async function toggleDetail(name) {
  expanded.value[name] = !expanded.value[name]
  if (expanded.value[name] && !columnsMap.value[name]) {
    try {
      const data = await api.get('/admin/schema/tables/' + encodeURIComponent(name) + '/columns')
      columnsMap.value[name] = Array.isArray(data) ? data : []
    } catch (e) {
      columnsMap.value[name] = []
    }
  }
}

async function loadTables() {
  try {
    const data = await api.get('/admin/schema/tables')
    tables.value = Array.isArray(data) ? data : []
  } catch (e) {
    tablesError.value = e.message || '加载失败'
  }
}

// 每表一键生成：自动选中该表并立即生成
function generateOne(name) {
  selected.value = [name]
  generate()
}

async function generate() {
  error.value = ''
  saveMsg.value = ''
  savedFlag.value = false
  if (!selected.value.length) {
    error.value = '请先选择数据表'
    return
  }
  generating.value = true
  try {
    result.value = await api.post('/admin/capabilities/ai-generate', {
      description: description.value.trim(),
      tables: selected.value
    })
  } catch (e) {
    error.value = e.message || '生成失败'
  } finally {
    generating.value = false
  }
}

async function save() {
  saving.value = true
  error.value = ''
  try {
    const payload = JSON.parse(JSON.stringify(def.value))
    payload.status = 'draft'
    await api.post('/admin/capabilities', payload)
    savedFlag.value = true
    saveMsg.value = '已保存草稿并提交审批，审批通过后自动上线'
    emit('saved', payload.id)
  } catch (e) {
    error.value = e.message || '保存失败'
  } finally {
    saving.value = false
  }
}

onMounted(loadTables)
</script>

<style scoped>
.ai-panel { display: grid; grid-template-columns: 5fr 6fr; gap: 14px; align-items: start; }
.input-card, .preview-card { padding: 18px; }
.card-title { font-size: 15px; font-weight: 600; margin-bottom: 12px; display: flex; align-items: center; gap: 6px; flex-wrap: wrap; }
.spark { color: #4F46E5; }
.title-sub { font-size: 11px; color: var(--text-3); font-weight: 400; }
.field-label { font-size: 12px; color: var(--text-3); margin: 12px 0 6px; }
.desc-input {
  width: 100%; padding: 10px 12px; font-size: 13px; font-family: inherit;
  border: 1px solid #E5E5E5; border-radius: 8px; outline: none; resize: vertical;
}
.desc-input:focus { border-color: var(--primary); }
.tpl-row { display: flex; align-items: center; gap: 6px; flex-wrap: wrap; margin-bottom: 6px; }
.tpl-chip {
  font-size: 11px; padding: 3px 10px; border: 1px dashed #c7d2fe; border-radius: 12px;
  background: #eef2ff; color: #4F46E5; cursor: pointer;
}
.tpl-chip:hover { background: #e0e7ff; }
.picker-search { margin-bottom: 8px; }
.picker-search input {
  width: 100%; padding: 7px 12px; font-size: 12px; border: 1px solid #E5E5E5;
  border-radius: 8px; outline: none;
}
.picker-list { max-height: 380px; overflow-y: auto; border: 1px solid #EEF2F0; border-radius: 8px; }
.picker-block { border-bottom: 1px solid #F7F8FA; padding: 6px 12px; }
.picker-block.checked { background: var(--primary-light); }
.picker-item { display: flex; align-items: center; gap: 8px; font-size: 12px; }
.picker-item:hover { background: transparent; }
.t-name { font-family: "SF Mono", Consolas, monospace; color: var(--text-1); font-weight: 600; }
.t-comment { font-size: 11px; color: var(--text-3); padding: 2px 0 4px 24px; }
.detail-btn {
  margin-left: auto; border: none; background: none; cursor: pointer;
  font-size: 11px; color: var(--text-3); padding: 2px 6px; white-space: nowrap;
}
.detail-btn:hover { color: var(--primary); }
.gen-one {
  font-size: 11px; padding: 3px 10px; white-space: nowrap;
  color: #4F46E5; border-color: #c7d2fe; background: #eef2ff;
}
.gen-one:hover { background: #e0e7ff; border-color: #818cf8; color: #4338ca; }
.col-detail { padding: 6px 0 8px 24px; }
.col-loading { font-size: 11px; color: var(--text-3); padding: 4px 0; }
.col-row { display: flex; gap: 10px; font-size: 11px; padding: 3px 0; align-items: baseline; }
.c-name { color: var(--text-1); min-width: 150px; }
.mono { font-family: "SF Mono", Consolas, monospace; }
.c-type { color: #4F46E5; min-width: 90px; }
.c-comment { color: var(--text-3); overflow: hidden; text-overflow: ellipsis; white-space: nowrap; }
.picker-empty { padding: 20px; text-align: center; color: var(--text-3); font-size: 12px; }
.gen-error { color: var(--danger); font-size: 12px; margin-top: 10px; }
.gen-loading {
  text-align: center; padding: 70px 20px;
  display: flex; flex-direction: column; align-items: center; gap: 12px;
}
.spin-ring {
  width: 38px; height: 38px; border-radius: 50%;
  border: 3px solid #e0e7ff; border-top-color: #4F46E5;
  animation: spin 0.9s linear infinite;
}
.loading-title { font-size: 14px; font-weight: 600; color: var(--text-2); }
.loading-sub { font-size: 12px; color: var(--text-3); }
.btn-spin {
  display: inline-block; width: 12px; height: 12px; border-radius: 50%;
  border: 2px solid rgba(255,255,255,.4); border-top-color: #fff;
  animation: spin 0.8s linear infinite; vertical-align: -2px; margin-right: 6px;
}
.gen-one .btn-spin { border-color: rgba(79,70,229,.3); border-top-color: #4F46E5; }
@keyframes spin { to { transform: rotate(360deg); } }
.gen-btn { width: 100%; margin-top: 14px; padding: 10px; font-size: 14px; }

.preview-empty { text-align: center; padding: 60px 20px; color: var(--text-3); }
.empty-icon { font-size: 34px; margin-bottom: 10px; }
.empty-title { font-size: 15px; font-weight: 600; color: var(--text-2); margin-bottom: 6px; }
.empty-desc { font-size: 12px; line-height: 1.8; }
.mock-banner {
  background: #fffbeb; border: 1px solid #fcd34d; color: #92400e;
  font-size: 12px; padding: 8px 12px; border-radius: 8px; margin-bottom: 12px;
}
.result-head { display: flex; justify-content: space-between; align-items: flex-start; margin-bottom: 8px; }
.r-id { font-size: 12px; color: var(--text-3); }
.r-name { font-size: 16px; font-weight: 600; }
.r-desc { font-size: 12px; color: var(--text-2); margin: 0 0 10px; }
.valid-pill {
  font-size: 11px; padding: 3px 10px; border-radius: 10px;
  background: var(--primary-light); color: var(--primary-deep); font-weight: 600;
}
.r-grid { display: grid; grid-template-columns: repeat(4, 1fr); gap: 8px; margin-bottom: 6px; }
.r-item {
  background: #F9FAFB; border-radius: 8px; padding: 8px 10px; font-size: 13px;
  display: flex; flex-direction: column; gap: 2px;
}
.r-item span { font-size: 11px; color: var(--text-3); }
.sql-view {
  background: #0F1B2D; color: #d7e4dd; font-family: "SF Mono", Consolas, monospace;
  font-size: 12px; line-height: 1.7; padding: 12px 14px; border-radius: 8px;
  overflow-x: auto; white-space: pre-wrap; word-break: break-all;
  max-height: 280px; overflow-y: auto;
}
.save-ok { color: var(--primary-deep); background: var(--primary-light); font-size: 12px; padding: 8px 12px; border-radius: 8px; margin-top: 10px; }
.r-actions { display: flex; gap: 10px; margin-top: 14px; justify-content: flex-end; }
@media (max-width: 1100px) {
  .ai-panel { grid-template-columns: 1fr; }
}
</style>

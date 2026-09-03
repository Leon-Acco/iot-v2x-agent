<template>
  <!-- 能力编辑抽屉：基础信息 / 别名 chips / 参数 schema / 限流四键 / SQL 模板 -->
  <div class="cap-editor">
    <section class="form-section">
      <div class="section-title">基础信息</div>
      <label class="form-field">
        <span>ID{{ form.id ? '' : ' *' }}</span>
        <input v-model.trim="form.id" :disabled="!!original" placeholder="snake_case" />
      </label>
      <label class="form-field">
        <span>显示名 *</span>
        <input v-model.trim="form.display" />
      </label>
      <label class="form-field">
        <span>描述</span>
        <textarea v-model="form.description" rows="2"></textarea>
      </label>
      <div class="field-row">
        <label class="form-field">
          <span>域</span>
          <input v-model.trim="form.domain" placeholder="alarm / mileage / ..." />
        </label>
        <label class="form-field">
          <span>类型</span>
          <select v-model="form.kind">
            <option value="capability">能力</option>
            <option value="orchestration">编排</option>
          </select>
        </label>
      </div>
      <label class="form-field">
        <span>图表建议 (chartHint)</span>
        <select v-model="form.chartHint">
          <option v-for="t in chartHints" :key="t" :value="t">{{ t }}</option>
        </select>
      </label>
    </section>

    <section class="form-section">
      <div class="section-title">别名（回车添加）</div>
      <div class="chips-box">
        <span v-for="(a, i) in form.aliases" :key="i" class="alias-chip">
          {{ a }}<button class="chip-x" @click="form.aliases.splice(i, 1)">✕</button>
        </span>
        <input v-model="aliasInput" class="alias-input" placeholder="输入别名后回车" @keydown.enter.prevent="addAlias" />
      </div>
    </section>

    <section class="form-section">
      <div class="section-title">参数 schema</div>
      <div v-for="(prm, i) in form.params" :key="i" class="param-row">
        <input v-model.trim="prm.name" placeholder="参数名" class="p-name" />
        <select v-model="prm.type" class="p-type">
          <option v-for="t in paramTypes" :key="t" :value="t">{{ t }}</option>
        </select>
        <label class="p-req"><input type="checkbox" v-model="prm.required" /> 必填</label>
        <input v-model="prm.description" placeholder="说明" class="p-desc" />
        <button class="chip-x" @click="form.params.splice(i, 1)">✕</button>
      </div>
      <button class="btn" @click="addParam">+ 添加参数</button>
    </section>

    <section class="form-section">
      <div class="section-title">限流配置</div>
      <div class="limits-grid">
        <label v-for="lk in limitKeys" :key="lk.key" class="form-field">
          <span>{{ lk.label }}</span>
          <input v-model.number="form.limits[lk.key]" type="number" min="0" />
        </label>
      </div>
    </section>

    <section class="form-section">
      <div class="section-title">SQL 模板</div>
      <textarea v-model="form.sqlTemplate" class="sql-editor" rows="10" spellcheck="false"></textarea>
    </section>

    <div v-if="error" class="form-error">{{ error }}</div>
  </div>
</template>

<script setup>
const props = defineProps({
  original: { type: Object, default: null }
})
const emit = defineEmits(['save'])

const chartHints = ['table', 'bar', 'line', 'pie', 'area', 'scatter']
const paramTypes = ['string', 'number', 'daterange', 'array<string>', 'boolean']
const limitKeys = [
  { key: 'maxRows', label: 'maxRows 最大行数' },
  { key: 'timeoutMs', label: 'timeoutMs 超时' },
  { key: 'maxSpanDays', label: 'maxSpanDays 跨度天数' },
  { key: 'maxQps', label: 'maxQPS' }
]

const blank = () => ({
  id: '', kind: 'capability', display: '', description: '', aliases: [],
  domain: '', readonly: true, params: [], chartHint: 'table',
  limits: { maxRows: 500, timeoutMs: 15000, maxSpanDays: 90, maxQps: null },
  sqlTemplate: '', status: 'draft'
})

const form = ref(blank())
const aliasInput = ref('')
const error = ref('')

watch(() => props.original, (o) => {
  if (o) {
    const copy = JSON.parse(JSON.stringify(o))
    copy.limits = Object.assign({ maxRows: null, timeoutMs: null, maxSpanDays: null, maxQps: null }, copy.limits || {})
    copy.aliases = copy.aliases || []
    copy.params = copy.params || []
    form.value = copy
  } else {
    form.value = blank()
  }
  error.value = ''
}, { immediate: true })

function addAlias() {
  const v = aliasInput.value.trim()
  if (v && !form.value.aliases.includes(v)) form.value.aliases.push(v)
  aliasInput.value = ''
}

function addParam() {
  form.value.params.push({ name: '', type: 'string', required: false, description: '' })
}

function submit() {
  if (!form.value.id || !form.value.display) {
    error.value = 'ID 与显示名必填'
    return
  }
  emit('save', JSON.parse(JSON.stringify(form.value)))
}
defineExpose({ submit })
</script>

<style scoped>
.form-section { margin-bottom: 20px; }
.section-title { font-size: 13px; font-weight: 700; margin-bottom: 10px; color: var(--text-1); }
.form-field { display: block; margin-bottom: 10px; }
.form-field span { display: block; font-size: 12px; color: var(--text-2); margin-bottom: 4px; }
.form-field input, .form-field select, .form-field textarea {
  width: 100%; padding: 8px 10px; font-size: 13px;
  border: 1px solid #E5E5E5; border-radius: 8px; outline: none;
  background: #F3F3F5; font-family: inherit;
}
.form-field input:focus, .form-field textarea:focus, .form-field select:focus { border-color: var(--primary); }
.field-row { display: flex; gap: 10px; }
.field-row .form-field { flex: 1; }
.chips-box {
  display: flex; flex-wrap: wrap; gap: 6px; padding: 8px;
  border: 1px solid #E5E5E5; border-radius: 8px; background: #F3F3F5;
}
.alias-chip {
  display: inline-flex; align-items: center; gap: 4px;
  background: var(--primary-light); color: var(--primary);
  border-radius: 14px; padding: 3px 6px 3px 10px; font-size: 12px;
}
.chip-x { border: none; background: transparent; color: var(--text-3); cursor: pointer; font-size: 11px; padding: 2px 4px; }
.chip-x:hover { color: var(--danger); }
.alias-input { border: none; outline: none; background: transparent; flex: 1; min-width: 120px; font-size: 12px; }
.param-row { display: flex; align-items: center; gap: 6px; margin-bottom: 8px; }
.param-row input, .param-row select {
  padding: 6px 8px; font-size: 12px; border: 1px solid #E5E5E5;
  border-radius: 6px; background: #F3F3F5; outline: none;
}
.p-name { width: 110px; font-family: monospace; }
.p-type { width: 110px; }
.p-req { font-size: 12px; color: var(--text-2); display: flex; align-items: center; gap: 3px; white-space: nowrap; }
.p-desc { flex: 1; min-width: 0; }
.limits-grid { display: grid; grid-template-columns: repeat(2, minmax(0, 1fr)); gap: 0 12px; }
.sql-editor {
  width: 100%; font-family: "SF Mono", Consolas, monospace; font-size: 12px;
  padding: 10px; border: 1px solid #E5E5E5; border-radius: 8px;
  background: #111827; color: #E5E7EB; outline: none; resize: vertical;
}
.form-error { color: var(--danger); font-size: 12px; margin-top: 8px; }
</style>

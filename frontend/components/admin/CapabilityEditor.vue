<template>
  <!-- 能力编辑抽屉：全字段回显编辑（与 CapabilityDefinition 完整对齐，修复保存丢字段问题） -->
  <div class="cap-editor">
    <section class="form-section">
      <div class="section-title">基础信息</div>
      <div class="field-row">
        <label class="form-field">
          <span>ID{{ form.id ? '' : ' *' }}</span>
          <input v-model.trim="form.id" :disabled="!!original" placeholder="snake_case" />
        </label>
        <label class="form-field">
          <span>显示名 *</span>
          <input v-model.trim="form.display" />
        </label>
      </div>
      <label class="form-field">
        <span>描述（给模型看）</span>
        <textarea v-model="form.description" rows="2"></textarea>
      </label>
      <div class="field-row">
        <label class="form-field">
          <span>域</span>
          <input v-model.trim="form.domain" list="domain-list" placeholder="alarm / mileage / ..." />
          <datalist id="domain-list">
            <option v-for="d in domainOptions" :key="d" :value="d" />
          </datalist>
        </label>
        <label class="form-field">
          <span>类型</span>
          <select v-model="form.kind">
            <option value="capability">能力</option>
            <option value="orchestration">编排</option>
          </select>
        </label>
      </div>
      <div class="field-row">
        <label class="form-field">
          <span>图表建议 (chartHint)</span>
          <select v-model="form.chartHint">
            <option v-for="t in chartHints" :key="t" :value="t">{{ t }}</option>
          </select>
        </label>
        <label class="form-field">
          <span>负责人/来源</span>
          <input v-model.trim="form.owner" placeholder="数据组 / AI" />
        </label>
      </div>
    </section>

    <section class="form-section">
      <div class="section-title">别名与示例</div>
      <div class="chips-label">别名（囬车添加，召回语料）</div>
      <div class="chips-box">
        <span v-for="(a, i) in form.aliases" :key="i" class="chip">
          {{ a }}<button class="chip-x" @click="form.aliases.splice(i, 1)">✕</button>
        </span>
        <input v-model="aliasInput" class="chip-input" :placeholder="'输入别名后回车'" @keydown.enter.prevent="addChip(form.aliases, aliasInput); aliasInput = ''" />
      </div>
      <div class="chips-label">示例问题（前端空态/评测语料）</div>
      <div class="chips-box">
        <span v-for="(q, i) in form.sampleQuestions" :key="i" class="chip">
          {{ q }}<button class="chip-x" @click="form.sampleQuestions.splice(i, 1)">✕</button>
        </span>
        <input v-model="questionInput" class="chip-input" :placeholder="'输入示例问题后回车'" @keydown.enter.prevent="addChip(form.sampleQuestions, questionInput); questionInput = ''" />
      </div>
    </section>

    <section class="form-section">
      <div class="section-title">参数 schema</div>
      <div v-for="(prm, i) in form.params" :key="i" class="param-card">
        <div class="param-main">
          <input v-model.trim="prm.name" placeholder="参数名" class="p-name" />
          <select v-model="prm.type" class="p-type">
            <option v-for="t in paramTypes" :key="t" :value="t">{{ t }}</option>
          </select>
          <label class="p-req"><input type="checkbox" v-model="prm.required" /> 必填</label>
          <button class="chip-x" @click="form.params.splice(i, 1)">✕</button>
        </div>
        <div class="param-sub">
          <input v-model="prm.description" placeholder="说明" class="p-desc" />
          <input v-model.number="prm.maxSpanDays" type="number" min="0" placeholder="跨度上限(天)" class="p-num" />
          <input v-model.number="prm.maxItems" type="number" min="0" placeholder="数组上限" class="p-num" />
          <input v-model="prm.enumText" placeholder="枚举值(逗号分隔)" class="p-enum" />
        </div>
      </div>
      <button class="btn add-btn" @click="addParam">+ 添加参数</button>
    </section>

    <section class="form-section">
      <div class="section-title">返回结构</div>
      <div class="field-row">
        <label class="form-field">
          <span>形状 (shape)</span>
          <select v-model="form.returns.shape">
            <option v-for="s in shapes" :key="s" :value="s">{{ s }}</option>
          </select>
        </label>
      </div>
      <div v-for="(col, i) in form.returns.columns" :key="i" class="param-card">
        <div class="param-main">
          <input v-model.trim="col.name" placeholder="列名" class="p-name" />
          <select v-model="col.semantic" class="p-type">
            <option v-for="s in semantics" :key="s" :value="s">{{ s }}</option>
          </select>
          <button class="chip-x" @click="form.returns.columns.splice(i, 1)">✕</button>
        </div>
        <div class="param-sub">
          <input v-model="col.display" placeholder="显示名" class="p-desc" />
          <input v-model.trim="col.unit" placeholder="单位" class="p-num" />
          <input v-model.number="col.scale" type="number" min="0" placeholder="精度" class="p-num" />
        </div>
      </div>
      <button class="btn add-btn" @click="addColumn">+ 添加列</button>
    </section>

    <section class="form-section">
      <div class="section-title">权限与来源</div>
      <div class="chips-label">所需 scope</div>
      <div class="chips-box">
        <span v-for="(s, i) in form.scopes" :key="i" class="chip">
          {{ s }}<button class="chip-x" @click="form.scopes.splice(i, 1)">✕</button>
        </span>
        <input v-model="scopeInput" class="chip-input" placeholder="vehicle.xxx.read" @keydown.enter.prevent="addChip(form.scopes, scopeInput); scopeInput = ''" />
      </div>
      <div class="field-row">
        <label class="form-field">
          <span>行级权限 (rowFilterPolicy)</span>
          <select v-model="form.rowFilterPolicy">
            <option v-for="rp in rowFilterPolicies" :key="rp" :value="rp">{{ rp }}</option>
          </select>
        </label>
      </div>
      <div class="chips-label">来源表（sourceTables，时效水位用）</div>
      <div class="chips-box">
        <span v-for="(t, i) in form.sourceTables" :key="i" class="chip">
          {{ t }}<button class="chip-x" @click="form.sourceTables.splice(i, 1)">✕</button>
        </span>
        <input v-model="tableInput" class="chip-input" :placeholder="'表名回车添加'" @keydown.enter.prevent="addChip(form.sourceTables, tableInput); tableInput = ''" />
      </div>
    </section>

    <section class="form-section">
      <div class="section-title">策略</div>
      <div class="field-row">
        <label class="form-field">
          <span>时效类型</span>
          <select v-model="form.freshnessPolicy.type">
            <option v-for="f in freshnessTypes" :key="f" :value="f">{{ f }}</option>
          </select>
        </label>
        <label class="form-field">
          <span>预期延迟（分钟）</span>
          <input v-model.number="form.freshnessPolicy.expectedDelayMin" type="number" min="0" />
        </label>
      </div>
      <div class="field-row">
        <label class="form-field check-field">
          <input type="checkbox" v-model="form.cache.cacheable" />
          <span>可缓存</span>
        </label>
        <label class="form-field">
          <span>缓存 TTL（秒）</span>
          <input v-model.number="form.cache.ttlSeconds" type="number" min="0" />
        </label>
      </div>
      <div class="limits-grid">
        <label v-for="lk in limitKeys" :key="lk.key" class="form-field">
          <span>{{ lk.label }}</span>
          <input v-model.number="form.limits[lk.key]" type="number" min="0" />
        </label>
      </div>
    </section>

    <section class="form-section">
      <div class="section-title">SQL 模板</div>
      <textarea v-model="form.sqlTemplate" class="sql-editor" rows="12" spellcheck="false"></textarea>
      <div class="sql-tip">必须包含 ${acl_org_ids} 权限占位符与 LIMIT；禁止 SELECT *</div>
    </section>

    <div v-if="error" class="form-error">{{ error }}</div>
  </div>
</template>

<script setup>
const props = defineProps({
  original: { type: Object, default: null },
  preset: { type: Object, default: null }
})
const emit = defineEmits(['save'])

const chartHints = ['table', 'bar', 'line', 'pie', 'area', 'scatter', 'map', 'metric_card']
const paramTypes = ['string', 'int', 'number', 'boolean', 'daterange', 'array<string>']
const semantics = ['time', 'category', 'metric', 'geo_lng', 'geo_lat', 'id']
const shapes = ['table', 'metric', 'geo']
const rowFilterPolicies = ['by_org', 'by_fleet', 'by_vin']
const freshnessTypes = ['realtime', 't_plus_0', 't_plus_1']
const domainOptions = ['online', 'location', 'mileage', 'alarm', 'fault', 'charge', 'trip', 'geofence', 'schema', 'battery', 'energy', 'behavior', 'custom']
const limitKeys = [
  { key: 'maxRows', label: 'maxRows 最大行数' },
  { key: 'timeoutMs', label: 'timeoutMs 超时(ms)' },
  { key: 'maxSpanDays', label: 'maxSpanDays 跨度天数' },
  { key: 'maxScanRows', label: 'maxScanRows 扫描行数' },
  { key: 'rateLimitPerMinute', label: 'rateLimitPerMinute 每分钟上限' }
]

const blank = () => ({
  id: '', kind: 'capability', display: '', description: '', aliases: [],
  domain: '', readonly: true, chartHint: 'table', owner: '',
  params: [],
  returns: { shape: 'table', columns: [] },
  scopes: [], rowFilterPolicy: 'by_org', sourceTables: [], sampleQuestions: [],
  freshnessPolicy: { type: 't_plus_0', expectedDelayMin: 5 },
  cache: { cacheable: true, ttlSeconds: 300 },
  limits: { maxRows: 500, timeoutMs: 15000, maxSpanDays: 90, maxScanRows: 10000000, rateLimitPerMinute: 60 },
  sqlTemplate: '', status: 'draft'
})

const form = ref(blank())
const aliasInput = ref('')
const questionInput = ref('')
const scopeInput = ref('')
const tableInput = ref('')
const error = ref('')

// 回显填充：编辑时整体拷跑，缺省字段补默认值（防止保存时丢字段）
function fill(src) {
  const b = blank()
  if (!src) return b
  const copy = JSON.parse(JSON.stringify(src))
  copy.aliases = copy.aliases || []
  copy.sampleQuestions = copy.sampleQuestions || []
  copy.params = (copy.params || []).map(p => ({ ...p, enumText: (p.enumValues || []).join(',') }))
  copy.returns = Object.assign({ shape: 'table', columns: [] }, copy.returns || {})
  copy.returns.columns = copy.returns.columns || []
  copy.scopes = copy.scopes || []
  copy.sourceTables = copy.sourceTables || []
  copy.freshnessPolicy = Object.assign({ type: 't_plus_0', expectedDelayMin: 5 }, copy.freshnessPolicy || {})
  copy.cache = Object.assign({ cacheable: true, ttlSeconds: 300 }, copy.cache || {})
  copy.limits = Object.assign(b.limits, copy.limits || {})
  return Object.assign(b, copy)
}

watch(() => [props.original, props.preset], ([o, ps]) => {
  form.value = fill(o || ps)
  error.value = ''
}, { immediate: true, deep: true })

function addChip(list, val) {
  const v = (val || '').trim()
  if (v && !list.includes(v)) list.push(v)
}

function addParam() {
  form.value.params.push({ name: '', type: 'string', required: false, description: '', maxSpanDays: null, maxItems: null, enumText: '' })
}

function addColumn() {
  form.value.returns.columns.push({ name: '', semantic: 'category', display: '', unit: '', scale: null })
}

function submit() {
  if (!form.value.id || !form.value.display) {
    error.value = 'ID 与显示名必填'
    return
  }
  const out = JSON.parse(JSON.stringify(form.value))
  // 枚举文本 → enumValues 数组；清理临时字段
  out.params = out.params.map(p => {
    const { enumText, ...rest } = p
    const enums = (enumText || '').split(',').map(s => s.trim()).filter(Boolean)
    if (enums.length) rest.enumValues = enums
    return rest
  })
  emit('save', out)
}
defineExpose({ submit })
</script>

<style scoped>
.cap-editor { display: flex; flex-direction: column; gap: 16px; }
.form-section { border: 1px solid #EEF2F0; border-radius: 12px; padding: 14px; background: #FBFCFC; }
.section-title { font-size: 13px; font-weight: 600; color: var(--text-1); margin-bottom: 10px; }
.form-field { display: flex; flex-direction: column; gap: 5px; flex: 1; margin-bottom: 8px; }
.form-field > span { font-size: 12px; color: var(--text-3); }
.form-field input, .form-field textarea, .form-field select {
  padding: 7px 10px; font-size: 13px; border: 1px solid #E5E5E5; border-radius: 8px;
  background: #fff; outline: none; font-family: inherit;
}
.form-field input:focus, .form-field textarea:focus, .form-field select:focus { border-color: var(--primary); }
.field-row { display: flex; gap: 10px; }
.check-field { flex-direction: row; align-items: center; gap: 8px; justify-content: flex-start; padding-top: 22px; }
.chips-label { font-size: 12px; color: var(--text-3); margin: 6px 0; }
.chips-box {
  display: flex; flex-wrap: wrap; gap: 6px; padding: 8px;
  border: 1px solid #E5E5E5; border-radius: 8px; background: #fff; min-height: 40px;
}
.chip {
  display: inline-flex; align-items: center; gap: 4px; font-size: 12px;
  background: var(--primary-light); color: var(--primary-deep);
  padding: 3px 8px; border-radius: 6px;
}
.chip-x { border: none; background: none; cursor: pointer; color: inherit; font-size: 11px; padding: 0 2px; }
.chip-input { flex: 1; min-width: 120px; border: none; outline: none; font-size: 12px; padding: 3px; }
.param-card { border: 1px solid #EEF2F0; border-radius: 8px; padding: 8px; margin-bottom: 8px; background: #fff; }
.param-main { display: flex; gap: 8px; align-items: center; }
.param-sub { display: flex; gap: 8px; margin-top: 6px; }
.p-name { width: 150px; }
.p-type { width: 130px; }
.p-req { font-size: 12px; color: var(--text-2); display: flex; align-items: center; gap: 4px; white-space: nowrap; }
.p-desc { flex: 1; }
.p-num { width: 110px; }
.p-enum { flex: 1; }
.param-main input, .param-main select, .param-sub input {
  padding: 6px 8px; font-size: 12px; border: 1px solid #E5E5E5; border-radius: 6px; outline: none;
}
.add-btn { font-size: 12px; }
.limits-grid { display: grid; grid-template-columns: repeat(2, 1fr); gap: 4px 12px; }
.sql-editor {
  width: 100%; font-family: "SF Mono", Consolas, monospace; font-size: 12px; line-height: 1.6;
  padding: 10px 12px; border: 1px solid #E5E5E5; border-radius: 8px; outline: none;
  background: #0F1B2D; color: #d7e4dd; resize: vertical;
}
.sql-tip { font-size: 11px; color: var(--text-3); margin-top: 6px; }
.form-error { color: var(--danger); font-size: 12px; }
</style>

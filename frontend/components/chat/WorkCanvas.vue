<template>
  <!-- center work canvas: query result + all visualizations with captions -->
  <section class="work-canvas">
    <div class="wc-head">
      <span class="wc-title">{{ title }}</span>
      <span class="wc-head-ops">
        <span v-if="dataAsOf" class="wc-asof">
          {{ dataAsOf }}<template v-if="delayHours != null">（延迟约 {{ delayHours }} 小时）</template>
        </span>
        <button class="wc-op-btn" type="button" title="重跑最近一次查询，拿最新数据" @click="$emit('refresh')">刷新</button>
        <button v-if="result" class="wc-op-btn" type="button" title="导出 CSV（数据表全文）" @click="exportCsv">CSV</button>
        <button v-if="!isEmpty" class="wc-op-btn" type="button" title="将数据工作台内容长截图导出为 PDF" :disabled="exporting" @click="exportPdf">{{ exporting ? '导出中…' : '导出 PDF' }}</button>
      </span>
    </div>

    <div v-if="isEmpty" class="wc-empty">
      <div class="wc-empty-icon">&#128202;</div>
      <div class="wc-empty-text">{{ emptyText }}</div>
      <div class="wc-empty-sub">点击图表中的柱子/折点，可发起针对性追问</div>
    </div>

    <div v-else ref="bodyRef" class="wc-body">
      <!-- agent trace card: execution steps from AGENT_TRACE frame -->
      <div v-if="traceSteps.length" class="wc-trace">
        <div class="wc-trace-title">执行轨迹</div>
        <div class="wc-trace-steps">
          <div v-for="(s, i) in traceSteps" :key="i" class="wc-trace-step">
            <span class="wc-trace-icon" :class="s.status === 'ok' ? 'ok' : 'err'">
              {{ s.status === 'ok' ? '✓' : '✗' }}
            </span>
            <span class="wc-trace-name">{{ s.type }}<template v-if="s.name"> · {{ s.name }}</template></span>
            <span class="wc-trace-ms">{{ s.duration_ms }}ms</span>
          </div>
        </div>
      </div>

      <!-- visualization cards (generate_visualization outputs) -->
      <div v-if="visualizations.length" class="wc-viz-grid">
        <VisualizationRenderer v-for="(v, i) in visualizations" :key="i" :vis="v" />
      </div>

      <!-- query result: chart + type switch + table -->
      <div v-if="result" class="wc-result">
        <div class="wc-card">
          <div class="wc-card-head">
            <span class="wc-card-title">{{ resultTitle }}</span>
            <span class="wc-switch">
              <button
                v-for="t in chartTypes"
                :key="t"
                class="wc-switch-btn"
                :class="{ on: activeChartType === t }"
                type="button"
                @click="pickChartType(t)"
              >{{ chartTypeLabel(t) }}</button>
            </span>
            <span v-if="resultCaption" class="wc-card-caption">{{ resultCaption }}</span>
          </div>
          <ChartPanel v-if="chartOption" :option="chartOption" height="300px" @point-click="onPointClick" />
          <div class="wc-table-wrap">
            <DataTable
              :columns="result.columns || []"
              :rows="result.rows || []"
              :row-count="result.rowCount"
              :truncated="!!result.truncated"
            />
          </div>
        </div>
      </div>
    </div>
  </section>
</template>

<script setup>
const props = defineProps({
  stream: { type: Object, default: null },
  sampleHint: { type: String, default: '' }
})
const emit = defineEmits(['drill', 'refresh'])

const EMPTY = '提问后，这里展示图表、地图、表格与分析结果'

const visualizations = computed(() => {
  if (!props.stream || !props.stream.visualizations) return []
  return props.stream.visualizations.value
})
const result = computed(() => (props.stream && props.stream.result ? props.stream.result.value : null))
const traceSteps = computed(() => {
  if (!props.stream || !props.stream.traceSteps) return []
  return props.stream.traceSteps.value
})
const isEmpty = computed(() => !visualizations.value.length && !result.value && !traceSteps.value.length)
const emptyText = computed(() => props.sampleHint || EMPTY)

const title = computed(() => {
  if (visualizations.value.length) return visualizations.value[0].title || '数据工作台'
  return '数据工作台'
})
const dataAsOf = computed(() => {
  const f = result.value && result.value.freshness
  return f && f.dataAsOf ? '数据截至 ' + f.dataAsOf : ''
})
// 数据延迟：当前时间 - 数据截至时间（小时）
const delayHours = computed(() => {
  const f = result.value && result.value.freshness
  if (!f || !f.dataAsOf) return null
  const t = Date.parse(String(f.dataAsOf).replace(' ', 'T'))
  if (isNaN(t)) return null
  return Math.max(0, Math.round((Date.now() - t) / 3600000))
})
const resultTitle = computed(() => {
  if (!result.value) return ''
  return result.value.capabilityId || '查询结果'
})
const resultCaption = computed(() => {
  if (!result.value) return ''
  const n = result.value.rowCount
  return '共 ' + (n == null ? 0 : n) + ' 行数据' + (result.value.truncated ? '（已截断）' : '')
})

// 图表类型切换：默认取后端 chartType；每个数据集记住上次选择（localStorage per capability）
const chartTypes = ['bar', 'line', 'area', 'table']
const activeChartType = ref('bar')
const bodyRef = ref(null)

function tabKey(r) {
  return 'v2x.chart.tab.' + ((r && r.capabilityId) || 'default')
}

watch(result, (r) => {
  let t = (r && r.chart && r.chart.chartType) || 'bar'
  try {
    const saved = localStorage.getItem(tabKey(r))
    if (saved && chartTypes.includes(saved)) t = saved
  } catch (e) { /* ignore */ }
  if (!chartTypes.includes(t)) t = 'bar'
  activeChartType.value = t
})

function pickChartType(t) {
  activeChartType.value = t
  try { localStorage.setItem(tabKey(result.value), t) } catch (e) { /* ignore */ }
}

function chartTypeLabel(t) {
  const labels = { bar: '柱状', line: '折线', area: '面积', table: '表格' }
  return labels[t] || t
}

const chartOption = computed(() => {
  if (!result.value || !result.value.columns) return null
  if (activeChartType.value === 'table') return null
  return buildChartOption(activeChartType.value, result.value.columns, result.value.rows)
})

// ---------- 导出 ----------
/** CSV 导出：表头 + 全部行（与对话结论同一数据源） */
function exportCsv() {
  const r = result.value
  if (!r || !r.columns) return
  const cols = r.columns.map(c => (c.display || c.name) + (c.unit ? '(' + c.unit + ')' : ''))
  const lines = [cols.join(',')]
  for (const row of (r.rows || [])) {
    lines.push(row.map(cell => {
      const s = cell == null ? '' : String(cell)
      return /[",\n]/.test(s) ? '"' + s.replace(/"/g, '""') + '"' : s
    }).join(','))
  }
  const blob = new Blob(['﻿' + lines.join('\n')], { type: 'text/csv;charset=utf-8' })
  const a = document.createElement('a')
  a.href = URL.createObjectURL(blob)
  a.download = ((r.capabilityId || 'query') + '-' + new Date().toISOString().slice(0, 10)) + '.csv'
  a.click()
  setTimeout(() => URL.revokeObjectURL(a.href), 5000)
}

/** PDF 导出：wc-body 整卡长截图 -> A4 分页 PDF（html2canvas + jsPDF 按需动态加载） */
const exporting = ref(false)
async function exportPdf() {
  if (exporting.value || !bodyRef.value) return
  exporting.value = true
  try {
    const [{ default: html2canvas }, { jsPDF }] = await Promise.all([import('html2canvas'), import('jspdf')])
    const canvas = await html2canvas(bodyRef.value, { scale: 2, backgroundColor: '#ffffff', useCORS: true })
    const img = canvas.toDataURL('image/jpeg', 0.92)
    const pdf = new jsPDF({ unit: 'mm', format: 'a4', compress: true })
    const pw = pdf.internal.pageSize.getWidth()
    const ph = pdf.internal.pageSize.getHeight()
    const m = 8
    const iw = pw - m * 2
    const ih = iw * canvas.height / canvas.width
    // 长图分页：整图按页高步进错位绘制，页边界自然裁切
    let offset = 0
    while (offset < ih - 0.1) {
      if (offset > 0) pdf.addPage()
      pdf.addImage(img, 'JPEG', m, m - offset, iw, ih)
      offset += ph - m * 2
    }
    pdf.save(((result.value && result.value.capabilityId) || 'workbench') + '-' + new Date().toISOString().slice(0, 10) + '.pdf')
  } catch (e) {
    window.alert('导出失败：' + (e && e.message ? e.message : e))
  } finally {
    exporting.value = false
  }
}

// ---------- 图表 -> 对话联动 ----------
/** 点柱/折点下钻：把维度值变成可直接执行的追问，回显到对话流 */
function onPointClick(params) {
  const name = params && params.name
  if (!name) return
  const isDate = /^\d{4}[-/]\d{1,2}[-/]\d{1,2}/.test(String(name))
  const q = isDate ? '看 ' + name + ' 当天的明细' : '只看' + name
  emit('drill', q)
}
</script>

<style scoped>
.work-canvas {
  min-width: 0; display: flex; flex-direction: column;
  padding: 18px 22px; overflow-y: auto;
  background: var(--panel);
  border: 1px solid var(--line); border-radius: 16px;
}
.wc-head {
  display: flex; align-items: center; justify-content: space-between;
  margin-bottom: 14px; gap: 10px; flex-wrap: wrap;
}
.wc-title { font-size: 16px; font-weight: 700; color: var(--text-1, #171717); }
.wc-head-ops { display: inline-flex; align-items: center; gap: 8px; flex-wrap: wrap; }
.wc-asof { font-size: 11px; color: var(--text-3, #a1a1a1); }
.wc-op-btn {
  height: 24px; padding: 0 11px; border-radius: 999px;
  border: 1px solid var(--border-default, #e5e7eb); background: #fff;
  color: var(--text-2, #737373); font: inherit; font-size: 11px; cursor: pointer;
}
.wc-op-btn:hover { border-color: var(--green-deep); color: var(--green-ink); }
.wc-empty {
  flex: 1; display: flex; flex-direction: column; align-items: center; justify-content: center;
  color: var(--text-3, #a1a1a1); gap: 12px;
}
.wc-empty-icon { font-size: 42px; opacity: .5; }
.wc-empty-text { font-size: 13px; }
.wc-empty-sub { font-size: 11.5px; opacity: .75; }
.wc-body { display: flex; flex-direction: column; gap: 16px; }
.wc-viz-grid { display: grid; grid-template-columns: repeat(auto-fit, minmax(min(360px, 100%), 1fr)); gap: 14px; }
.wc-card {
  background: #fff; border: 1px solid var(--border-default, #e5e7eb);
  border-radius: 12px; padding: 14px 16px;
}
.wc-card-head { margin-bottom: 10px; }
.wc-card-title { font-size: 14px; font-weight: 600; color: var(--text-1, #171717); }
.wc-card-caption { display: block; font-size: 12px; color: var(--text-3, #737373); margin-top: 2px; }
.wc-switch { display: inline-flex; gap: 4px; margin-left: 10px; vertical-align: middle; }
.wc-switch-btn {
  height: 22px; padding: 0 10px; border-radius: 999px;
  border: 1px solid var(--border-default, #e5e7eb); background: #fff;
  color: var(--text-3, #737373); font: inherit; font-size: 11px; cursor: pointer;
}
.wc-switch-btn:hover { border-color: var(--green-deep); color: var(--green-ink); }
.wc-switch-btn.on {
  background: rgba(23, 160, 94, .12); border-color: rgba(23, 160, 94, .6);
  color: var(--green-ink, #0E6E46); font-weight: 600;
}
.wc-table-wrap { margin-top: 12px; }
</style>

<style scoped>
.wc-trace { margin-bottom: 12px; padding: 10px 14px; background: rgba(255,255,255,.72); border-radius: 10px; }
.wc-trace-title { font-size: 12px; font-weight: 600; color: #1A2B22; margin-bottom: 6px; }
.wc-trace-steps { display: flex; flex-direction: column; gap: 4px; }
.wc-trace-step { display: flex; align-items: center; gap: 8px; font-size: 12px; color: #5C7266; }
.wc-trace-icon { width: 16px; text-align: center; }
.wc-trace-icon.ok { color: #17A05E; }
.wc-trace-icon.err { color: #D64545; }
.wc-trace-name { flex: 1; }
.wc-trace-ms { color: #8AA294; font-variant-numeric: tabular-nums; }
</style>

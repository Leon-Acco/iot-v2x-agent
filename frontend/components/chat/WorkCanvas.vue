<template>
  <!-- center work canvas: session ledger grouped by question round (流水账) -->
  <section class="work-canvas">
    <div class="wc-head">
      <span class="wc-title">数据工作台</span>
      <span class="wc-head-ops">
        <span v-if="dataAsOf" class="wc-asof">
          {{ dataAsOf }}<template v-if="delayHours != null">（延迟约 {{ delayHours }} 小时）</template>
        </span>
        <button class="wc-op-btn" type="button" title="重跑最近一次查询，拿最新数据" @click="$emit('refresh')">刷新</button>
        <button v-if="!isEmpty" class="wc-op-btn" type="button" title="将数据工作台内容长截图导出为 PDF" :disabled="exporting" @click="exportPdf">{{ exporting ? '导出中…' : '导出 PDF' }}</button>
      </span>
    </div>

    <div v-if="isEmpty" class="wc-empty">
      <div class="wc-empty-icon">&#128202;</div>
      <div class="wc-empty-text">{{ emptyText }}</div>
      <div class="wc-empty-sub">会话内每次提问的结果、图表与可视化会按轮次展示在这里</div>
    </div>

    <div v-else ref="bodyRef" class="wc-body">
      <!-- 轮次流水：新 -> 旧，最新轮在最上，默认展开最新 2 轮 -->
      <div
        v-for="r in orderedRounds"
        :key="r.id"
        class="wc-round"
        :class="{ folded: !isRoundOpen(r) }"
      >
        <!-- 轮头：时间 + 问题原文 + 能力/工具徽章 + 折叠 -->
        <div class="wc-round-head" @click="toggleRound(r)">
          <span class="wc-round-time">{{ fmtTime(r.ts) }}</span>
          <span class="wc-round-q">{{ r.question || '本轮提问' }}</span>
          <span class="wc-round-badges">
            <span v-for="c in r.capabilities" :key="c" class="wc-cap-pill" :title="'调用能力：' + c">{{ c }}</span>
          </span>
          <span class="wc-round-count">{{ r.items.length }} 项</span>
          <span class="wc-round-toggle">{{ isRoundOpen(r) ? '收起' : '展开' }}</span>
        </div>

        <!-- 轮内条目 -->
        <div v-if="isRoundOpen(r)" class="wc-round-items">
          <template v-for="(item, idx) in r.items" :key="r.id + '#' + idx">
            <!-- 查询结果条目：图表切换 + 表格 + CSV -->
            <div v-if="item.kind === 'result'" class="wc-card">
              <div class="wc-card-head">
                <span class="wc-card-title">{{ item.label }}</span>
                <span v-if="item.toolName" class="wc-tool-pill" :title="'工具：' + item.toolName">{{ item.toolName }}</span>
                <span class="wc-switch">
                  <button
                    v-for="t in chartTypes"
                    :key="t"
                    class="wc-switch-btn"
                    :class="{ on: chartTypeOf(r, idx, item) === t }"
                    type="button"
                    @click="pickChartType(r, idx, item, t)"
                  >{{ chartTypeLabel(t) }}</button>
                </span>
                <span class="wc-card-ops">
                  <span v-if="itemCaption(item)" class="wc-card-caption">{{ itemCaption(item) }}</span>
                  <button class="wc-op-btn" type="button" title="导出该结果 CSV" @click="exportCsv(item)">CSV</button>
                  <button class="wc-viz-x" type="button" title="从工作台移除" @click="$emit('remove-item', r.id, idx)">×</button>
                </span>
              </div>
              <ChartPanel
                v-if="buildOptionOf(r, idx, item)"
                :option="buildOptionOf(r, idx, item)"
                height="300px"
                @point-click="onPointClick"
              />
              <div v-if="item.data" class="wc-table-wrap">
                <DataTable
                  :columns="item.data.columns || []"
                  :rows="item.data.rows || []"
                  :row-count="item.data.rowCount"
                  :truncated="!!item.data.truncated"
                />
              </div>
            </div>

            <!-- 可视化条目 -->
            <div v-else-if="item.kind === 'viz'" class="wc-card">
              <div class="wc-card-head">
                <span class="wc-card-title">{{ item.label }}</span>
                <span class="wc-tool-pill">{{ item.toolName === 'generate_visualization' ? '可视化工具' : (item.toolName || '可视化') }}</span>
                <span class="wc-card-ops">
                  <button class="wc-viz-x" type="button" title="从工作台移除" @click="$emit('remove-item', r.id, idx)">×</button>
                </span>
              </div>
              <VisualizationRenderer :vis="item.data" />
              <div v-if="item.data && item.data.caption" class="wc-viz-caption">{{ item.data.caption }}</div>
              <div v-if="item.data && item.data.analysis" class="wc-viz-analysis">
                <span class="wc-viz-analysis-tag">AI 分析</span>
                <span class="wc-viz-analysis-text">{{ item.data.analysis }}</span>
              </div>
            </div>
          </template>
        </div>
      </div>
    </div>
  </section>
</template>

<script setup>
const props = defineProps({
  rounds: { type: Array, default: () => [] },
  sampleHint: { type: String, default: '' }
})
const emit = defineEmits(['drill', 'refresh', 'remove-item'])

const EMPTY = '提问后，这里按轮次展示图表、地图、表格与分析结果'

// ---------- 轮次排列（新 -> 旧）与展开状态 ----------
const orderedRounds = computed(() => [...props.rounds].reverse())
const DEFAULT_OPEN = 2
const roundState = ref({}) // roundId -> open（仅记录用户显式操作过的轮）

function isRoundOpen(r) {
  if (r.id in roundState.value) return !!roundState.value[r.id]
  return props.rounds.length - props.rounds.indexOf(r) <= DEFAULT_OPEN
}

function toggleRound(r) {
  roundState.value = Object.assign({}, roundState.value, { [r.id]: !isRoundOpen(r) })
}

const isEmpty = computed(() => !props.rounds.length)
const emptyText = computed(() => props.sampleHint || EMPTY)

// 新鲜度：取最新一轮里最新的结果条目
const latestResult = computed(() => {
  for (const r of orderedRounds.value) {
    for (const item of r.items) {
      if (item.kind === 'result' && item.data) return item.data
    }
  }
  return null
})
const dataAsOf = computed(() => {
  const f = latestResult.value && latestResult.value.freshness
  return f && f.dataAsOf ? '数据截至 ' + f.dataAsOf : ''
})
const delayHours = computed(() => {
  const f = latestResult.value && latestResult.value.freshness
  if (!f || !f.dataAsOf) return null
  const t = Date.parse(String(f.dataAsOf).replace(' ', 'T'))
  if (isNaN(t)) return null
  return Math.max(0, Math.round((Date.now() - t) / 3600000))
})

function fmtTime(ts) {
  const d = new Date(ts)
  const p = n => String(n).padStart(2, '0')
  return p(d.getHours()) + ':' + p(d.getMinutes())
}

function itemCaption(item) {
  if (!item.data || item.data.rowCount == null) return ''
  return '共 ' + item.data.rowCount + ' 行数据' + (item.data.truncated ? '（已截断）' : '')
}

// ---------- 图表类型切换：每个结果条目独立，localStorage per capability 记忆 ----------
const chartTypes = ['bar', 'line', 'area', 'table']
const chartState = ref({}) // itemKey -> chartType

function itemKey(r, idx) {
  return r.id + '#' + idx
}

function chartTypeOf(r, idx, item) {
  const k = itemKey(r, idx)
  if (k in chartState.value) return chartState.value[k]
  let t = (item.chart && item.chart.chartType) || 'bar'
  try {
    const saved = localStorage.getItem('v2x.chart.tab.' + ((item.data && item.data.capabilityId) || 'default'))
    if (saved && chartTypes.includes(saved)) t = saved
  } catch (e) { /* ignore */ }
  if (!chartTypes.includes(t)) t = 'bar'
  return t
}

function pickChartType(r, idx, item, t) {
  chartState.value = Object.assign({}, chartState.value, { [itemKey(r, idx)]: t })
  try { localStorage.setItem('v2x.chart.tab.' + ((item.data && item.data.capabilityId) || 'default'), t) } catch (e) { /* ignore */ }
}

function chartTypeLabel(t) {
  const labels = { bar: '柱状', line: '折线', area: '面积', table: '表格' }
  return labels[t] || t
}

/** 结果条目的 ECharts option（table 类型不出图）；chartType 每条目独立记忆 */
function buildOptionOf(r, idx, item) {
  if (!item.data || !item.data.columns) return null
  const t = chartTypeOf(r, idx, item)
  if (t === 'table') return null
  return buildChartOption(t, item.data.columns, item.data.rows)
}

// ---------- 导出 ----------
/** CSV 导出：单个结果条目（表头 + 全部行） */
function exportCsv(item) {
  const r = item.data
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
const bodyRef = ref(null)
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
    let offset = 0
    while (offset < ih - 0.1) {
      if (offset > 0) pdf.addPage()
      pdf.addImage(img, 'JPEG', m, m - offset, iw, ih)
      offset += ph - m * 2
    }
    pdf.save('workbench-' + new Date().toISOString().slice(0, 10) + '.pdf')
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
  const q = isDate ? '查看 ' + name + ' 当天的明细' : '只看' + name
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
.wc-body { display: flex; flex-direction: column; gap: 14px; }
/* 轮次流水：组头 + 条目列表 */
.wc-round {
  border: 1px solid var(--border-default, #e5e7eb);
  border-radius: 12px; background: rgba(255, 255, 255, .55);
  overflow: hidden;
}
.wc-round-head {
  display: flex; align-items: center; gap: 10px;
  padding: 9px 14px; cursor: pointer; min-width: 0;
  background: rgba(23, 160, 94, .05);
}
.wc-round-head:hover { background: rgba(23, 160, 94, .09); }
.wc-round-time {
  flex-shrink: 0; font-size: 11px; color: var(--text-3, #a1a1a1);
  font-variant-numeric: tabular-nums;
}
.wc-round-q {
  flex: 1; min-width: 0; overflow: hidden; text-overflow: ellipsis; white-space: nowrap;
  font-size: 13px; font-weight: 600; color: var(--text-1, #171717);
}
.wc-round-badges { display: inline-flex; gap: 5px; flex-wrap: wrap; flex-shrink: 0; }
.wc-cap-pill {
  padding: 1px 8px; border-radius: 999px;
  background: rgba(23, 160, 94, .12); color: var(--green-ink, #0E6E46);
  font-size: 11px; white-space: nowrap;
}
.wc-round-count { flex-shrink: 0; font-size: 11px; color: var(--text-3, #a1a1a1); }
.wc-round-toggle { flex-shrink: 0; font-size: 11px; color: var(--green-ink, #0E6E46); }
.wc-round-items { display: flex; flex-direction: column; gap: 12px; padding: 12px 14px; }
/* 条目卡片 */
.wc-card {
  background: #fff; border: 1px solid var(--border-default, #e5e7eb);
  border-radius: 12px; padding: 14px 16px;
}
.wc-card-head { margin-bottom: 10px; display: flex; align-items: center; gap: 10px; flex-wrap: wrap; }
.wc-card-title { font-size: 14px; font-weight: 600; color: var(--text-1, #171717); }
.wc-tool-pill {
  padding: 1px 8px; border-radius: 999px;
  background: rgba(37, 99, 235, .08); color: #1D4ED8;
  font-family: var(--f-mono, monospace); font-size: 10.5px; white-space: nowrap;
}
.wc-card-ops { display: inline-flex; align-items: center; gap: 8px; margin-left: auto; }
.wc-card-caption { font-size: 12px; color: var(--text-3, #737373); }
.wc-switch { display: inline-flex; gap: 4px; }
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
.wc-viz-x {
  width: 20px; height: 20px; border: none; border-radius: 6px;
  background: transparent; color: var(--text-3, #a1a1a1);
  font-size: 14px; line-height: 1; cursor: pointer; padding: 0;
}
.wc-viz-x:hover { background: #fee2e2; color: #dc2626; }
.wc-viz-caption { margin-top: 6px; font-size: 12px; color: var(--text-3, #737373); }
.wc-viz-analysis {
  margin-top: 8px; display: flex; gap: 8px;
  padding: 8px 10px; border-radius: 6px;
  background: rgba(23, 160, 94, .06);
  border-left: 3px solid rgba(23, 160, 94, .55);
  font-size: 12.5px; color: #1A2B22; line-height: 1.55;
}
.wc-viz-analysis-tag { flex-shrink: 0; font-weight: 600; color: var(--green-ink, #0E6E46); }
</style>

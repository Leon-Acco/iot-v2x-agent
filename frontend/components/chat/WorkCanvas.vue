<template>
  <!-- center work canvas: query result + all visualizations with captions -->
  <section class="work-canvas">
    <div class="wc-head">
      <span class="wc-title">{{ title }}</span>
      <span v-if="dataAsOf" class="wc-asof">{{ dataAsOf }}</span>
    </div>

    <div v-if="isEmpty" class="wc-empty">
      <div class="wc-empty-icon">&#128202;</div>
      <div class="wc-empty-text">{{ emptyText }}</div>
    </div>

    <div v-else class="wc-body">
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
                @click="activeChartType = t"
              >{{ chartTypeLabel(t) }}</button>
            </span>
            <span v-if="resultCaption" class="wc-card-caption">{{ resultCaption }}</span>
          </div>
          <ChartPanel v-if="chartOption" :option="chartOption" height="300px" />
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
const resultTitle = computed(() => {
  if (!result.value) return ''
  return result.value.capabilityId || '查询结果'
})
const resultCaption = computed(() => {
  if (!result.value) return ''
  const n = result.value.rowCount
  return '共 ' + (n == null ? 0 : n) + ' 行数据' + (result.value.truncated ? '（已截断）' : '')
})

// 图表类型切换：默认取后端 chartType，前端可在 折线/柱状/面积/表格 间切换（自构 option 红线）
const chartTypes = ['bar', 'line', 'area', 'table']
const activeChartType = ref('bar')

watch(result, (r) => {
  activeChartType.value = (r && r.chart && r.chart.chartType) || 'bar'
  if (!chartTypes.includes(activeChartType.value)) activeChartType.value = 'bar'
})

function chartTypeLabel(t) {
  const labels = { bar: '柱状', line: '折线', area: '面积', table: '表格' }
  return labels[t] || t
}

const chartOption = computed(() => {
  if (!result.value || !result.value.columns) return null
  if (activeChartType.value === 'table') return null
  return buildChartOption(activeChartType.value, result.value.columns, result.value.rows)
})
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
  margin-bottom: 14px;
}
.wc-title { font-size: 16px; font-weight: 700; color: var(--text-1, #171717); }
.wc-asof { font-size: 11px; color: var(--text-3, #a1a1a1); }
.wc-empty {
  flex: 1; display: flex; flex-direction: column; align-items: center; justify-content: center;
  color: var(--text-3, #a1a1a1); gap: 12px;
}
.wc-empty-icon { font-size: 42px; opacity: .5; }
.wc-empty-text { font-size: 13px; }
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

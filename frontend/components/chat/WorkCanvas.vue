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
      <!-- visualization cards (generate_visualization outputs) -->
      <div v-if="visualizations.length" class="wc-viz-grid">
        <VisualizationRenderer v-for="(v, i) in visualizations" :key="i" :vis="v" />
      </div>

      <!-- query result: chart + table -->
      <div v-if="result" class="wc-result">
        <div class="wc-card">
          <div class="wc-card-head">
            <span class="wc-card-title">{{ resultTitle }}</span>
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
const isEmpty = computed(() => !visualizations.value.length && !result.value)
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

const chartOption = computed(() => {
  if (!result.value || !result.value.columns) return null
  const type = (result.value.chart && result.value.chart.chartType) || 'bar'
  if (type === 'table') return null
  return buildChartOption(type, result.value.columns, result.value.rows)
})
</script>

<style scoped>
.work-canvas {
  flex: 1; min-width: 0; display: flex; flex-direction: column;
  padding: 18px 22px; overflow-y: auto;
  border-right: 1px solid var(--border-default, #e5e7eb);
  background: var(--bg-page, #f7f9fb);
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
.wc-viz-grid { display: grid; grid-template-columns: repeat(auto-fit, minmax(360px, 1fr)); gap: 14px; }
.wc-card {
  background: #fff; border: 1px solid var(--border-default, #e5e7eb);
  border-radius: 12px; padding: 14px 16px;
}
.wc-card-head { margin-bottom: 10px; }
.wc-card-title { font-size: 14px; font-weight: 600; color: var(--text-1, #171717); }
.wc-card-caption { display: block; font-size: 12px; color: var(--text-3, #737373); margin-top: 2px; }
.wc-table-wrap { margin-top: 12px; }
</style>

<template>
  <!-- 三区联动结果面板：平白 + 左侧分隔线，明细表 / 图表 / 参数微调 -->
  <aside class="result-panel">
    <div class="panel-tabs">
      <button
        v-for="t in tabs"
        :key="t.key"
        class="tab-btn"
        :class="{ active: tab === t.key }"
        @click="tab = t.key"
      >{{ t.label }}</button>
    </div>

    <div v-if="!result" class="panel-empty">提问后此处展示明细与图表</div>

    <template v-else>
      <div v-show="tab === 'table'" class="tab-body">
        <DataTable
          :columns="result.columns || []"
          :rows="result.rows || []"
          :row-count="result.rowCount"
          :truncated="!!result.truncated"
        />
      </div>
      <div v-show="tab === 'chart'" class="tab-body">
        <div v-if="chartTypes.length" class="chart-switch">
          <button
            v-for="ct in chartTypes"
            :key="ct"
            class="chart-type-btn"
            :class="{ active: chartType === ct }"
            @click="chartType = ct"
          >{{ CHART_TYPE_LABELS[ct] || ct }}</button>
        </div>
        <ChartPanel v-if="chartOption" :option="chartOption" height="300px" />
        <div v-else class="panel-empty">当前数据不适合生成图表</div>
      </div>
      <div v-show="tab === 'params'" class="tab-body">
        <ParamTuner :stream="stream" />
      </div>
    </template>
  </aside>
</template>

<script setup>
const props = defineProps({
  stream: { type: Object, required: true }
})

const tabs = [
  { key: 'table', label: '明细表' },
  { key: 'chart', label: '图表' },
  { key: 'params', label: '参数微调' }
]
const tab = ref('table')
const chartType = ref('')

const result = computed(() => props.stream.result.value)

const chartTypes = computed(() => {
  const alt = result.value && result.value.chart && Array.isArray(result.value.chart.chartAlternatives)
    ? result.value.chart.chartAlternatives
    : []
  const supported = alt.filter(t => t !== 'table' && CHART_TYPE_LABELS[t])
  if (supported.length) return supported
  return result.value && result.value.columns ? ['bar', 'line', 'pie'] : []
})

const chartOption = computed(() => {
  if (!result.value) return null
  const type = chartType.value || defaultChartType.value
  return buildChartOption(type, result.value.columns, result.value.rows)
})

const defaultChartType = computed(() => {
  const fromBackend = result.value && result.value.chart && result.value.chart.chartType
  if (fromBackend && chartTypes.value.includes(fromBackend)) return fromBackend
  return chartTypes.value[0] || 'bar'
})

watch(result, (r) => {
  chartType.value = ''
  if (r && r.chart && r.chart.chartType) tab.value = 'chart'
  else if (r) tab.value = 'table'
})
</script>

<style scoped>
.result-panel {
  width: 400px; flex-shrink: 0;
  background: rgba(255, 255, 255, 0.55);
  border-left: 1px solid var(--border-default);
  display: flex; flex-direction: column; overflow: hidden;
}
.panel-tabs {
  display: flex; gap: 2px; padding: 10px 14px;
  border-bottom: 1px solid var(--border-light); flex-shrink: 0;
}
.tab-btn {
  border: none; background: transparent; padding: 6px 12px; border-radius: 6px;
  font-size: 13px; color: var(--text-2); cursor: pointer;
}
.tab-btn:hover { background: var(--bg-hover); }
.tab-btn.active { background: var(--primary-soft); color: var(--text-1); font-weight: 500; }
.panel-empty {
  flex: 1; display: flex; align-items: center; justify-content: center;
  color: var(--text-3); font-size: 12px; padding: 30px 16px; text-align: center;
}
.tab-body { flex: 1; overflow: auto; padding: 12px 14px; min-height: 0; }
.chart-switch { display: flex; gap: 6px; margin-bottom: 10px; flex-wrap: wrap; }
.chart-type-btn {
  border: 1px solid var(--border-default); background: #fff; border-radius: 999px;
  padding: 4px 12px; font-size: 12px; color: var(--text-2); cursor: pointer;
}
.chart-type-btn:hover { border-color: var(--border-strong); }
.chart-type-btn.active { background: var(--primary); border-color: var(--primary); color: #fff; }
</style>

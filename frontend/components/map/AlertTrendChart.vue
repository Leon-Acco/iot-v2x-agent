<template>
  <!-- 告警趋势：ECharts 面积曲线，24 小时补齐缺小时 -->
  <div ref="el" class="trend"></div>
</template>

<script setup>
const props = defineProps({
  trend: { type: Array, default: () => [] }
})
const el = ref(null)
const { mount, setOption, resize, dispose } = useChart()

const option = computed(() => {
  const byHour = {}
  for (const t of props.trend) byHour[Number(t.hh)] = Number(t.cnt) || 0
  const now = new Date().getHours()
  const hours = []
  for (let i = 23; i >= 0; i--) hours.push((now - i + 48) % 24)
  const labels = hours.map(h => (h < 10 ? '0' + h : '' + h) + ':00')
  const data = hours.map(h => byHour[h] || 0)
  return {
    grid: { left: 6, right: 10, top: 10, bottom: 2, containLabel: true },
    tooltip: { trigger: 'axis', textStyle: { fontSize: 11 } },
    xAxis: {
      type: 'category', data: labels, boundaryGap: false,
      axisLabel: { fontSize: 10, color: '#5A7194', interval: 5 },
      axisLine: { lineStyle: { color: 'rgba(56,189,248,.18)' } }, axisTick: { show: false }
    },
    yAxis: {
      type: 'value',
      splitLine: { lineStyle: { color: 'rgba(56,189,248,.08)' } },
      axisLabel: { fontSize: 10, color: '#5A7194' }
    },
    series: [{
      type: 'line', data: data, smooth: true, symbol: 'none',
      lineStyle: { color: '#2DD4BF', width: 1.8 },
      areaStyle: {
        color: {
          type: 'linear', x: 0, y: 0, x2: 0, y2: 1,
          colorStops: [
            { offset: 0, color: 'rgba(45,212,191,.32)' },
            { offset: 1, color: 'rgba(45,212,191,0)' }
          ]
        }
      }
    }]
  }
})

onMounted(async () => {
  await mount(el.value)
  setOption(option.value)
  window.addEventListener('resize', resize)
})
onBeforeUnmount(() => {
  window.removeEventListener('resize', resize)
  dispose()
})
watch(option, (o) => setOption(o))
</script>

<style scoped>
.trend { width: 100%; height: 100%; min-height: 120px; }
</style>

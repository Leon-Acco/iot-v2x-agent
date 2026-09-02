<template>
  <!-- 左侧数据面板：在线率仪表 + 品牌分布饼图 -->
  <PanelCard title="在线率">
    <ChartPanel :option="gaugeOption" height="170px" />
  </PanelCard>
  <PanelCard title="品牌分布" style="margin-top:12px">
    <ChartPanel :option="pieOption" height="250px" />
  </PanelCard>
</template>

<script setup>
const props = defineProps({
  vehicles: { type: Array, default: () => [] },
  brands: { type: Array, default: () => [] }
})

const PIE_COLORS = ['#2563EB', '#3B82F6', '#F59E0B', '#22C55E', '#93C5FD', '#93C5FD', '#A1A1A1', '#D4D4D4']

const onlineCount = computed(() => props.vehicles.filter(v => Number(v.online) === 1).length)
const rate = computed(() => {
  const total = props.vehicles.length
  return total ? Math.round((onlineCount.value / total) * 100) : 0
})

const gaugeOption = computed(() => ({
  series: [{
    type: 'gauge',
    startAngle: 210, endAngle: -30, min: 0, max: 100,
    radius: '100%', center: ['50%', '62%'],
    progress: { show: true, width: 10, itemStyle: { color: '#2563EB' } },
    axisLine: { lineStyle: { width: 10, color: [[1, '#E5E5E5']] } },
    axisTick: { show: false }, splitLine: { show: false }, axisLabel: { show: false },
    pointer: { show: false }, anchor: { show: false },
    title: { show: true, offsetCenter: [0, '30%'], fontSize: 11, color: '#A1A1A1' },
    detail: {
      valueAnimation: true, fontSize: 26, fontWeight: 700, color: '#0F1B2D',
      offsetCenter: [0, '-8%'], formatter: '{value}%'
    },
    data: [{ value: rate.value, name: '当前在线比例' }]
  }]
}))

// 品牌分布：优先用后端 brands，缺省从车辆明细聚合
const brandData = computed(() => {
  if (props.brands && props.brands.length) {
    return props.brands.slice(0, 7).map(b => ({ name: b.brand, value: Number(b.cnt) || 0 }))
  }
  const counter = {}
  for (const v of props.vehicles) {
    const brand = v.car_brand_name || '未知'
    counter[brand] = (counter[brand] || 0) + 1
  }
  return Object.entries(counter).sort((a, b) => b[1] - a[1]).slice(0, 7)
    .map(([name, value]) => ({ name, value }))
})

const pieOption = computed(() => ({
  color: PIE_COLORS,
  tooltip: { trigger: 'item', formatter: '{b}: {c} 台 ({d}%)' },
  legend: {
    bottom: 0, itemWidth: 10, itemHeight: 10, icon: 'circle',
    textStyle: { fontSize: 11, color: '#737373' }
  },
  series: [{
    type: 'pie',
    radius: ['44%', '68%'], center: ['50%', '44%'],
    itemStyle: { borderRadius: 4, borderColor: '#fff', borderWidth: 2 },
    label: { show: false },
    emphasis: { label: { show: true, fontSize: 12, fontWeight: 600 } },
    data: brandData.value
  }]
}))
</script>

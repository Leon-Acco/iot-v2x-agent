<template>
  <!-- ECharts 图表容器：option 驱动，自动随窗口 resize；透传点击事件、支持导出 PNG -->
  <div ref="el" class="chart-panel" :style="{ height }"></div>
</template>

<script setup>
const props = defineProps({
  option: { type: Object, default: null },
  height: { type: String, default: '220px' }
})
const emit = defineEmits(['point-click'])
const el = ref(null)
const { mount, setOption, resize, dispose, getChart } = useChart()
let ready = false

onMounted(async () => {
  await mount(el.value)
  ready = true
  if (props.option) setOption(props.option)
  // 点击柱子/折点：透传给上层（下钻追问联动）
  const chart = getChart()
  if (chart) {
    chart.on('click', params => {
      if (params && params.name) emit('point-click', params)
    })
  }
  window.addEventListener('resize', resize)
})
watch(() => props.option, (opt) => { if (ready && opt) setOption(opt) }, { deep: true })
onBeforeUnmount(() => {
  window.removeEventListener('resize', resize)
  dispose()
})

/** 导出 PNG（对齐 ECharts 实例尺寸，含 2 倍像素密度） */
function exportPng(filename) {
  const chart = getChart()
  if (!chart) return
  const url = chart.getDataURL({ type: 'png', pixelRatio: 2, backgroundColor: '#fff' })
  const a = document.createElement('a')
  a.href = url
  a.download = (filename || 'chart') + '.png'
  a.click()
}
defineExpose({ exportPng })
</script>

<style scoped>
.chart-panel { width: 100%; }
</style>

<template>
  <!-- ECharts 图表容器：option 驱动，自动随窗口 resize -->
  <div ref="el" class="chart-panel" :style="{ height }"></div>
</template>

<script setup>
const props = defineProps({
  option: { type: Object, default: null },
  height: { type: String, default: '220px' }
})
const el = ref(null)
const { mount, setOption, resize, dispose } = useChart()
let ready = false

onMounted(async () => {
  await mount(el.value)
  ready = true
  if (props.option) setOption(props.option)
  window.addEventListener('resize', resize)
})
watch(() => props.option, (opt) => { if (ready && opt) setOption(opt) }, { deep: true })
onBeforeUnmount(() => {
  window.removeEventListener('resize', resize)
  dispose()
})
</script>

<style scoped>
.chart-panel { width: 100%; }
</style>

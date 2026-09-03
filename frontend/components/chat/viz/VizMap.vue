<template>
  <div ref="el" class="viz-map"></div>
</template>

<script setup>
const props = defineProps({
  points: { type: Array, default: () => [] }
})
const el = ref(null)
const { mount, setOption, resize, dispose } = useChart()
let ready = false

onMounted(async () => {
  if (import.meta.server || !el.value) return
  const echarts = await import('echarts')
  if (!echarts.getMap('china')) {
    const geo = await fetch('/geo/china.json').then(r => r.json())
    echarts.registerMap('china', geo)
  }
  await mount(el.value)
  ready = true
  const data = props.points.map(p => ({
    name: p.name || '', value: [p.lng, p.lat, p.value != null ? p.value : 1]
  }))
  setOption({
    geo: { map: 'china', roam: true, itemStyle: { areaColor: '#EFF6FF', borderColor: '#BFDBFE' } },
    series: [{
      type: 'scatter', coordinateSystem: 'geo', data,
      symbolSize: v => Math.min(8 + Math.sqrt(v[2] || 1) * 2, 26),
      itemStyle: { color: '#2563EB', opacity: 0.75 }
    }]
  })
  window.addEventListener('resize', resize)
})

onBeforeUnmount(() => {
  window.removeEventListener('resize', resize)
  dispose()
})
</script>

<style scoped>
.viz-map { width: 100%; height: 340px; }
</style>

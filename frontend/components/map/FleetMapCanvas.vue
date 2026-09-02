<template>
  <!-- fleet distribution map: china base + heat/scatter mode switch -->
  <div class="fleet-wrap">
    <div ref="el" class="fleet-map"></div>
    <div class="mode-switch" role="group" aria-label="分布模式切换">
      <button :class="{ on: mode === 'heat' }" @click="setMode('heat')">热力图</button>
      <button :class="{ on: mode === 'scatter' }" @click="setMode('scatter')">分布点</button>
    </div>
    <div v-if="error" class="map-error">{{ error }}</div>
  </div>
</template>

<script setup>
const props = defineProps({
  vehicles: { type: Array, default: () => [] },
  palette: { type: Object, default: null }
})

const el = ref(null)
const error = ref('')
const mode = ref('heat')
let chart = null
let echarts = null
let ready = false
const GEO_BASE = 'https://geo.datav.aliyun.com/areas_v3/bound/'

const PAL = computed(() => props.palette || {
  light: '#CFE8DE', mid: '#3FB48D', deep: '#0E7A5D',
  area: '#EDF6F1', hover: '#8FD3BC', provArea: '#F0F7F3', provHover: '#BFDFD1'
})

onMounted(async () => {
  try {
    echarts = await import('echarts')
    chart = echarts.init(el.value)
    const resp = await fetch(GEO_BASE + '100000_full.json')
    if (!resp.ok) throw new Error('geojson ' + resp.status)
    echarts.registerMap('china', await resp.json())
    ready = true
    render()
    window.addEventListener('resize', resize)
  } catch (e) {
    error.value = '地图加载失败'
  }
})

onBeforeUnmount(() => {
  window.removeEventListener('resize', resize)
  if (chart) { chart.dispose(); chart = null }
})

watch(() => props.vehicles, () => render())

function resize() { if (chart) chart.resize() }

// heat color scale follows the current data (95th percentile)
const heatMax = computed(() => {
  const counts = {}
  for (const v of props.vehicles) {
    if (typeof v.lng !== 'number' || typeof v.lat !== 'number') continue
    const key = v.lng.toFixed(1) + ',' + v.lat.toFixed(1)
    counts[key] = (counts[key] || 0) + 1
  }
  const vals = Object.values(counts).sort((a, b) => a - b)
  if (!vals.length) return 6
  const p95 = vals[Math.floor(vals.length * 0.95)] || 1
  return Math.max(3, Math.ceil(p95 * 1.5))
})
function setMode(m) { mode.value = m; render() }

// split vehicles into heat points / online / offline scatter points
const pts = computed(() => {
  const on = []
  const off = []
  const heat = []
  for (const v of props.vehicles) {
    if (typeof v.lng !== 'number' || typeof v.lat !== 'number') continue
    heat.push([v.lng, v.lat, 1])
    const p = { name: v.plate_no || v.vin, value: [v.lng, v.lat], raw: v }
    ;(Number(v.online) === 1 ? on : off).push(p)
  }
  return { on, off, heat }
})

function baseGeo() {
  return {
    map: 'china', roam: true,
    zoom: 1.05, scaleLimit: { min: 0.8, max: 6 },
    label: { show: false },
    itemStyle: { borderColor: '#FFFFFF', borderWidth: 1, areaColor: PAL.value.area },
    emphasis: { label: { show: false }, itemStyle: { areaColor: PAL.value.hover } }
  }
}

function render() {
  if (!ready || !chart) return
  if (mode.value === 'heat') {
    chart.setOption({
      backgroundColor: 'transparent',
      tooltip: { show: false },
      geo: baseGeo(),
      visualMap: {
        show: false, min: 0, max: heatMax.value, seriesIndex: 0,
        inRange: { color: ['rgba(143, 211, 188, 0)', '#8FD3BC', '#3FB48D', '#0E7A5D'] }
      },
      series: [{
        type: 'heatmap', coordinateSystem: 'geo',
        data: pts.value.heat, pointSize: 6, blurSize: 9
      }]
    }, { notMerge: true })
  } else {
    chart.setOption({
      backgroundColor: 'transparent',
      tooltip: {
        trigger: 'item',
        formatter: (p) => {
          const v = p.data && p.data.raw
          if (!v) return p.name || ''
          const loc = [v.province_name, v.city_name].filter(Boolean).join(' / ')
          return '<b>' + (v.plate_no || v.vin) + '</b><br/>' + loc + '<br/>' + (Number(v.online) === 1 ? '在线' : '离线')
        }
      },
      geo: baseGeo(),
      series: [
        {
          type: 'scatter', coordinateSystem: 'geo',
          symbolSize: 5,
          itemStyle: { color: '#9DB8AE', borderColor: '#fff', borderWidth: 1, opacity: 0.7 },
          data: pts.value.off, zlevel: 2
        },
        {
          type: 'effectScatter', coordinateSystem: 'geo',
          symbolSize: 7,
          rippleEffect: { brushType: 'stroke', scale: 2.4 },
          itemStyle: { color: PAL.value.deep, borderColor: '#fff', borderWidth: 1, shadowBlur: 4, shadowColor: 'rgba(14, 122, 93, .5)' },
          data: pts.value.on, zlevel: 3
        }
      ]
    }, { notMerge: true })
  }
}
</script>

<style scoped>
.fleet-wrap { position: absolute; inset: 0; }
.fleet-map { width: 100%; height: 100%; }
.mode-switch {
  position: absolute; left: 14px; bottom: 14px; z-index: 5; display: flex; gap: 5px;
  background: rgba(255, 255, 255, .85); border: 1px solid rgba(16, 60, 48, .10);
  border-radius: 10px; padding: 4px; box-shadow: 0 1px 2px rgba(16, 60, 48, .04);
}
.mode-switch button {
  font: inherit; font-size: 12px; color: #5C6B66; background: none; border: 0;
  border-radius: 7px; padding: 6px 11px; cursor: pointer;
}
.mode-switch button.on { background: #EAF6F1; color: #0B6E55; font-weight: 600; }
.map-error { position: absolute; inset: 0; display: grid; place-items: center; color: #8C9994; font-size: 13px; }
</style>

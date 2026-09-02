<template>
  <!-- 深色孪生地图：ECharts Geo 全国热力/散点 · 省级钻取 · 在线呼吸粒子 -->
  <div class="vmap-wrap">
    <div ref="el" class="vmap"></div>
    <div class="mode-switch" role="group" aria-label="切换分布模式">
      <button :class="{ on: mode === 'scatter' }" @click="setMode('scatter')">散点</button>
      <button :class="{ on: mode === 'heat' }" @click="setMode('heat')">热力</button>
    </div>
    <div v-if="drilled" class="back-chip" @click="backToChina">← 返回全国</div>
    <div v-if="error" class="map-error">{{ error }}</div>
  </div>
</template>

<script setup>
const props = defineProps({
  vehicles: { type: Array, default: () => [] },
  provinces: { type: Array, default: () => [] }
})
const emit = defineEmits(['drill', 'select-vehicle'])

const el = ref(null)
const error = ref('')
const drilled = ref(false)
const mode = ref('scatter')
let chart = null
let echarts = null
let chinaGeo = null
let drilledName = ''
let focusVin = ''

const GEO_BASE = 'https://geo.datav.aliyun.com/areas_v3/bound/'
const GEO_LOCAL = '/geo/'

// 本地优先拉取 GeoJSON，失败回退远程（内网可用）
async function fetchGeo(file) {
  try {
    const r = await fetch(GEO_LOCAL + file)
    if (r.ok) return await r.json()
  } catch (e) { /* fall through to remote */ }
  const r2 = await fetch(GEO_BASE + file)
  if (!r2.ok) throw new Error('geojson ' + r2.status)
  return r2.json()
}

onMounted(async () => {
  try {
    echarts = await import('echarts')
    chart = echarts.init(el.value)
    chart.on('click', onMapClick)
    chinaGeo = await fetchGeo('100000_full.json')
    echarts.registerMap('china', chinaGeo)
    renderChina()
    window.addEventListener('resize', resize)
  } catch (e) {
    error.value = '地图加载失败'
  }
})

onBeforeUnmount(() => {
  window.removeEventListener('resize', resize)
  if (chart) { chart.dispose(); chart = null }
})

watch(() => props.provinces, () => { if (!drilled.value) renderChina() }, { deep: true })
watch(() => props.vehicles, () => {
  if (!chart || !chinaGeo) return
  if (drilled.value && drilledName) {
    const f = (chinaGeo.features || []).find(x => x.properties && x.properties.name === drilledName)
    if (f) renderProvince(drilledName, f)
  } else {
    renderChina()
  }
}, { deep: true })

function resize() { if (chart) chart.resize() }
function setMode(m) {
  mode.value = m
  if (!drilled.value) renderChina()
}

function statOf(name) {
  const p = props.provinces.find(x => x.province_name === name)
  return p ? { total: Number(p.total) || 0, online: Number(p.online) || 0 } : { total: 0, online: 0 }
}

// 在线分层：0=运行 1=停车 2=离线（与后端 online_level 口径一致）
function tierOf(v) {
  const t = Number(v.online_level)
  return isNaN(t) ? (Number(v.online) === 1 ? 0 : 2) : t
}

function splitTiers(list) {
  const run = [], park = [], lost = []
  for (const v of list) {
    if (typeof v.lng !== 'number' || typeof v.lat !== 'number') continue
    const pt = { name: v.plate_no || v.vin, value: [v.lng, v.lat], raw: v }
    const t = tierOf(v)
    if (t === 0) run.push(pt)
    else if (t === 1) park.push(pt)
    else lost.push(pt)
  }
  return { run, park, lost }
}

// 三层散点：离线琥珀 / 停车灰蓝 / 在线青绿呼吸涟漪
function scatterSeries(list) {
  const t = splitTiers(list)
  return [
    { type: 'scatter', coordinateSystem: 'geo', symbolSize: 5,
      itemStyle: { color: '#F5B544', opacity: 0.55, borderColor: 'rgba(7,13,26,.6)', borderWidth: 0.5 },
      data: t.lost, zlevel: 2 },
    { type: 'scatter', coordinateSystem: 'geo', symbolSize: 5,
      itemStyle: { color: '#5A7194', opacity: 0.7, borderColor: 'rgba(7,13,26,.6)', borderWidth: 0.5 },
      data: t.park, zlevel: 2 },
    { type: 'effectScatter', coordinateSystem: 'geo', symbolSize: 7,
      rippleEffect: { brushType: 'stroke', scale: 3.2 },
      itemStyle: { color: '#2DD4BF', shadowBlur: 8, shadowColor: 'rgba(45,212,191,.8)' },
      data: t.run, zlevel: 3 }
  ]
}

function vehicleTip(p) {
  if (p.seriesType === 'scatter' || p.seriesType === 'effectScatter') {
    const v = p.data.raw
    const loc = [v.province_name, v.city_name, v.district_name].filter(Boolean).join(' / ')
    const t = tierOf(v)
    const st = t === 0 ? '在线' : (t === 1 ? '停车' : '离线')
    return '<b>' + (v.plate_no || v.vin) + '</b><br/>VIN: ' + v.vin
      + (v.car_brand_name ? '<br/>' + v.car_brand_name + (v.car_model_name ? ' / ' + v.car_model_name : '') : '')
      + '<br/>' + (loc || '位置未知') + '<br/>' + st
  }
  const s = statOf(p.name)
  return s.total
    ? p.name + '<br/>车辆 ' + s.total + ' 台 · 在线 ' + s.online
    : p.name + '<br/>暂无车辆'
}

const mapBase = {
  roam: true, zoom: 1.05,
  scaleLimit: { min: 0.8, max: 8 },
  label: { show: false },
  itemStyle: { borderColor: 'rgba(56,189,248,.35)', borderWidth: 0.8, areaColor: '#0D1830' },
  emphasis: { label: { show: true, fontSize: 12, color: '#E6F1FF' }, itemStyle: { areaColor: '#1B3A5C' } },
  select: { disabled: true }
}

function renderChina() {
  if (!chart || !chinaGeo) return
  if (mode.value === 'heat') return renderChinaHeat()
  const max = Math.max(...props.provinces.map(p => Number(p.total) || 0), 1)
  chart.setOption({
    backgroundColor: 'transparent',
    tooltip: { trigger: 'item', formatter: vehicleTip },
    visualMap: {
      min: 0, max: max, left: 14, bottom: 14, calculable: false,
      text: ['车辆数', ''],
      textStyle: { color: '#8CA3C3', fontSize: 11 },
      inRange: { color: ['#10203F', '#155E75', '#2DD4BF'] }
    },
    series: [Object.assign({
      type: 'map', map: 'china',
      data: props.provinces.map(p => ({ name: p.province_name, value: Number(p.total) || 0 }))
    }, mapBase)].concat(scatterSeries(props.vehicles))
  }, { notMerge: true })
}

function renderChinaHeat() {
  const pts = props.vehicles
    .filter(v => typeof v.lng === 'number' && typeof v.lat === 'number')
    .map(v => [v.lng, v.lat, 1])
  chart.setOption({
    backgroundColor: 'transparent',
    tooltip: { trigger: 'item' },
    visualMap: {
      min: 0, max: 6, show: false, calculable: false, seriesIndex: 0,
      inRange: { color: ['rgba(21,94,117,.2)', 'rgba(45,212,191,.55)', 'rgba(52,245,197,.9)', '#E6FFFA'] }
    },
    geo: [Object.assign({ map: 'china' }, mapBase)],
    series: [{
      type: 'heatmap', coordinateSystem: 'geo',
      pointSize: 14, blurSize: 22,
      data: pts, zlevel: 2
    }]
  }, { notMerge: true })
}

function onMapClick(params) {
  if (params.seriesType === 'scatter' || params.seriesType === 'effectScatter') {
    if (params.data && params.data.raw) emit('select-vehicle', params.data.raw)
    return
  }
  if (drilled.value) return
  if (params.seriesType === 'map' && params.name) drillByName(params.name)
}

async function drillByName(name) {
  if (!chart || !chinaGeo) return
  const feature = (chinaGeo.features || []).find(f => f.properties && f.properties.name === name)
  if (!feature) return
  drilledName = name
  drilled.value = true
  await renderProvince(name, feature)
  emit('drill', { level: 'province', name })
}

async function renderProvince(name, feature) {
  try {
    const adcode = feature.properties.adcode
    const provGeo = await fetchGeo(adcode + '_full.json')
    echarts.registerMap('prov', provGeo)
    const list = props.vehicles.filter(v => (v.province_name || '') === name)
    chart.setOption({
      backgroundColor: 'transparent',
      tooltip: { trigger: 'item', formatter: vehicleTip },
      series: [Object.assign({ type: 'map', map: 'prov', data: [] }, mapBase, { zoom: 1, scaleLimit: { min: 0.8, max: 10 } })]
        .concat(scatterSeries(list))
    }, { notMerge: true })
    if (focusVin) showVinTip(focusVin)
  } catch (e) {
    error.value = '省份数据加载失败'
  }
}

function showVinTip(vin) {
  if (!chart) return
  const list = props.vehicles.filter(x => (x.province_name || '') === drilledName)
  const t = splitTiers(list)
  const groups = [t.lost, t.park, t.run]
  for (let i = 0; i < groups.length; i++) {
    const idx = groups[i].findIndex(p => p.raw.vin === vin)
    if (idx >= 0) {
      chart.dispatchAction({ type: 'showTip', seriesIndex: i + 1, dataIndex: idx })
      return
    }
  }
}

// 外部调用：告警定位车辆（钻取到省 + 弹出车辆详情）
async function panToVehicle(v) {
  if (!v || typeof v.lng !== 'number' || typeof v.lat !== 'number') return
  focusVin = v.vin
  const prov = v.province_name || ''
  if (!prov) return
  if (!drilled.value || drilledName !== prov) {
    await drillByName(prov)
  } else {
    showVinTip(v.vin)
  }
}

function backToChina() {
  drilled.value = false
  drilledName = ''
  focusVin = ''
  renderChina()
  emit('drill', { level: 'china', name: '' })
}

defineExpose({ panToVehicle, backToChina, drillByName })
</script>

<style scoped>
.vmap-wrap { position: absolute; inset: 0; }
.vmap { width: 100%; height: 100%; }
.mode-switch {
  position: absolute; right: 14px; top: 14px; z-index: 6; display: flex; gap: 4px;
  background: rgba(13, 24, 44, .78); border: 1px solid rgba(56, 189, 248, .22);
  border-radius: 10px; padding: 4px; backdrop-filter: blur(8px);
}
.mode-switch button {
  font: inherit; font-size: 12px; color: #8CA3C3; background: none; border: 0;
  border-radius: 7px; padding: 6px 12px; cursor: pointer;
}
.mode-switch button.on { background: rgba(45, 212, 191, .16); color: #2DD4BF; font-weight: 600; }
.back-chip {
  position: absolute; left: 14px; top: 14px; z-index: 6; cursor: pointer;
  background: rgba(13, 24, 44, .78); border: 1px solid rgba(56, 189, 248, .22);
  color: #2DD4BF; font-size: 12px; font-weight: 500;
  border-radius: 999px; padding: 7px 16px; backdrop-filter: blur(8px);
}
.back-chip:hover { background: rgba(45, 212, 191, .14); }
.map-error {
  position: absolute; inset: 0; display: flex; align-items: center; justify-content: center;
  color: #F87171; font-size: 13px;
}
</style>

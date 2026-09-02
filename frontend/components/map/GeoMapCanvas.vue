<template>
  <!-- ECharts Geo 地图：蓝色 choropleth + 省份钻取车辆散点 -->
  <div class="geo-wrap">
    <div ref="el" class="geo-map"></div>
    <div v-if="drilled" class="back-chip" @click="backToChina">← 返回全国</div>
    <div v-if="error" class="map-error">{{ error }}</div>
  </div>
</template>

<script setup>
const props = defineProps({
  vehicles: { type: Array, default: () => [] },
  provinces: { type: Array, default: () => [] },
  // 配色（可选）：light/mid/deep 渐变三档 + area 底色 + hover 悬浮色 + provArea/provHover 省级底色
  palette: { type: Object, default: null }
})
const emit = defineEmits(['select', 'drill'])

const el = ref(null)
const error = ref('')
const drilled = ref(false)
let chart = null
let echarts = null
let chinaGeo = null
let drilledName = ''

const GEO_BASE = 'https://geo.datav.aliyun.com/areas_v3/bound/'
const GEO_LOCAL = '/geo/'
// local-first fetch with remote fallback (works offline / intranet)
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

watch(() => props.provinces, () => { if (!drilled.value && chart && chinaGeo) renderChina() }, { deep: true })
watch(() => props.vehicles, () => { if (drilled.value && drilledName) renderProvince(drilledName, null) })

function resize() { if (chart) chart.resize() }

function statOf(name) {
  const p = props.provinces.find(x => x.province_name === name)
  return p ? { total: Number(p.total) || 0, online: Number(p.online) || 0 } : { total: 0, online: 0 }
}

// 默认蓝色系；传入 palette 时用页面主题色
const PAL = computed(() => props.palette || {
  light: '#DBEAFE', mid: '#93C5FD', deep: '#2563EB',
  area: '#EDF3FE', hover: '#93C5FD', provArea: '#F1F6FE', provHover: '#BFDBFE'
})

// 两个 hex 色之间按 t 插值
function mixHex(a, b, t) {
  const k = Math.min(1, Math.max(0, t))
  const pa = [1, 3, 5].map(i => parseInt(a.slice(i, i + 2), 16))
  const pb = [1, 3, 5].map(i => parseInt(b.slice(i, i + 2), 16))
  const c = pa.map((v, i) => Math.round(v + (pb[i] - v) * k))
  return 'rgb(' + c.join(',') + ')'
}

function renderChina() {
  const max = Math.max(...props.provinces.map(p => Number(p.total) || 0), 1)
  chart.setOption({
    backgroundColor: 'transparent',
    tooltip: {
      trigger: 'item',
      formatter: (p) => {
        const s = statOf(p.name)
        return s.total ? p.name + '<br/>车辆 ' + s.total + ' 台 · 在线 ' + s.online : p.name + '<br/>暂无车辆'
      }
    },
    visualMap: {
      min: 0, max: max, calculable: true,
      left: 16, bottom: 16,
      text: ['车辆数', ''],
      textStyle: { color: '#475569', fontSize: 11 },
      inRange: { color: [PAL.value.light, PAL.value.mid, PAL.value.deep] }
    },
    series: [{
      type: 'map', map: 'china', roam: true,
      zoom: 1.05, scaleLimit: { min: 0.8, max: 6 },
      label: { show: false },
      itemStyle: { borderColor: '#FFFFFF', borderWidth: 1, areaColor: PAL.value.area },
      emphasis: {
        label: { show: true, fontSize: 12, fontWeight: 600, color: '#0F1B2D' },
        itemStyle: { areaColor: PAL.value.hover }
      },
      select: { disabled: true },
      data: props.provinces.map(p => ({ name: p.province_name, value: Number(p.total) || 0 }))
    }]
  }, { notMerge: true })
}

function onMapClick(params) {
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
  const pv = props.provinces.find(x => x.province_name === name)
  emit('drill', pv || { province_name: name })
}

async function renderProvince(name, feature) {
  try {
    const adcode = feature.properties.adcode
    const provGeo = await fetchGeo(adcode + '_full.json')
    echarts.registerMap('prov', provGeo)

    const list = props.vehicles.filter(v => (v.province_name || '未知') === name)
    const cityCounter = {}
    for (const v of list) {
      const c = v.city_name || '未知'
      cityCounter[c] = (cityCounter[c] || 0) + 1
    }
    const cityMax = Math.max(...Object.values(cityCounter), 1)
    const regionColors = (provGeo.features || []).map(f => {
      const cname = f.properties.name
      const cnt = cityCounter[cname] || 0
      return { name: cname, itemStyle: { areaColor: cnt ? mixHex(PAL.value.light, PAL.value.deep, cnt / cityMax) : PAL.value.provArea } }
    })

    const onlinePts = []
    const offlinePts = []
    for (const v of list) {
      if (typeof v.lng !== 'number' || typeof v.lat !== 'number') continue
      const pt = { name: v.plate_no || v.vin, value: [v.lng, v.lat], raw: v }
      ;(Number(v.online) === 1 ? onlinePts : offlinePts).push(pt)
    }

    chart.setOption({
      backgroundColor: 'transparent',
      tooltip: {
        trigger: 'item',
        formatter: (p) => {
          if (p.seriesType === 'scatter' || p.seriesType === 'effectScatter') {
            const v = p.data.raw
            const loc = [v.province_name, v.city_name, v.district_name].filter(Boolean).join(' / ')
            const online = Number(v.online) === 1
            return '<b>' + (v.plate_no || v.vin) + '</b><br/>VIN: ' + v.vin
              + (v.car_brand_name ? '<br/>' + v.car_brand_name + (v.car_model_name ? ' / ' + v.car_model_name : '') : '')
              + '<br/>' + loc + '<br/>' + (online ? '在线' : '离线')
          }
          return p.name + '<br/>车辆 ' + (cityCounter[p.name] || 0) + ' 台'
        }
      },
      series: [
        {
          type: 'map', map: 'prov', roam: true,
          zoom: 1, scaleLimit: { min: 0.8, max: 8 },
          label: { show: false },
          itemStyle: { borderColor: '#FFFFFF', borderWidth: 1, areaColor: PAL.value.provArea },
          emphasis: { label: { show: true, fontSize: 11, color: '#0F1B2D' }, itemStyle: { areaColor: PAL.value.provHover } },
          select: { disabled: true },
          regions: regionColors,
          data: []
        },
        {
          type: 'scatter', coordinateSystem: 'geo',
          symbolSize: 6,
          itemStyle: { color: '#94A3B8', borderColor: '#fff', borderWidth: 1, opacity: 0.75 },
          data: offlinePts,
          zlevel: 2
        },
        {
          type: 'effectScatter', coordinateSystem: 'geo',
          symbolSize: 8,
          rippleEffect: { brushType: 'stroke', scale: 2.6 },
          itemStyle: { color: PAL.value.deep, borderColor: '#fff', borderWidth: 1, shadowBlur: 4, shadowColor: 'rgba(37,99,235,.5)' },
          data: onlinePts,
          zlevel: 3
        }
      ]
    }, { notMerge: true })
  } catch (e) {
    error.value = '省份数据加载失败'
  }
}

function backToChina() {
  drilled.value = false
  drilledName = ''
  renderChina()
  emit('drill', null)
}

defineExpose({ drillByName, backToChina })
</script>

<style scoped>
.geo-wrap { position: absolute; inset: 0; }
.geo-map { width: 100%; height: 100%; }
.back-chip {
  position: absolute; left: 14px; top: 14px; z-index: 6;
  background: #fff; border: 1px solid var(--border-default);
  border-radius: 999px; padding: 7px 16px; cursor: pointer;
  font-size: 12px; color: var(--primary); font-weight: 500;
  box-shadow: var(--card-shadow);
}
.back-chip:hover { background: var(--primary-light); }
.map-error {
  position: absolute; inset: 0; display: flex; align-items: center; justify-content: center;
  color: var(--danger); font-size: 13px;
}
</style>

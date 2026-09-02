<template>
  <!-- 高德地图：省级分布塞色（choropleth）+ 点击省份钻取车辆点，GCJ-02 直接渲染 -->
  <div class="amap-wrap">
    <div ref="mapEl" class="amap-container"></div>
    <div v-if="drilled" class="back-chip" @click="backToChina">
      ← 返回全国
    </div>
    <div v-if="loadError" class="map-error">{{ loadError }}</div>
  </div>
</template>

<script setup>
const props = defineProps({
  vehicles: { type: Array, default: () => [] },
  provinces: { type: Array, default: () => [] }
})
const emit = defineEmits(['select', 'drill'])

const mapEl = ref(null)
const loadError = ref('')
const drilled = ref(false)
const { load } = useAMap()
let map = null
let AMapRef = null
let districtSearch = null
let provincePolygons = []
let markers = []
let infoWindow = null
let fitted = false

onMounted(async () => {
  try {
    AMapRef = await load()
    if (!AMapRef) return
    map = new AMapRef.Map(mapEl.value, {
      viewMode: '3D',
      pitch: 35,
      zoom: 4.4,
      center: [106.5, 33.5],
      mapStyle: 'amap://styles/lightblue'
    })
    infoWindow = new AMapRef.InfoWindow({ offset: new AMapRef.Pixel(0, -6) })
    AMapRef.plugin('AMap.DistrictSearch', () => {
      districtSearch = new AMapRef.DistrictSearch({ extensions: 'all', level: 'province', subdistrict: 0 })
      renderChoropleth()
    })
  } catch (e) {
    loadError.value = '地图加载失败'
  }
})

onBeforeUnmount(() => {
  if (map) { map.destroy(); map = null }
})

watch(() => props.provinces, renderChoropleth)
watch(() => props.vehicles, () => { if (drilled.value) renderMarkers(currentProvince()) })

// 省份填色：按车辆数深测
function colorOf(count, max) {
  const t = max > 0 ? Math.min(1, count / max) : 0
  const lerp = (a, b) => Math.round(a + (b - a) * t)
  const r = lerp(0xDD, 0x2B)
  const g = lerp(0xE9, 0x7F)
  const b = lerp(0xFB, 0xFF)
  return 'rgb(' + r + ',' + g + ',' + b + ')'
}

function renderChoropleth() {
  if (!map || !districtSearch || !props.provinces.length) return
  provincePolygons.forEach(pg => map.remove(pg))
  provincePolygons = []
  const max = Math.max(...props.provinces.map(p => Number(p.total) || 0), 1)
  for (const pv of props.provinces) {
    const name = pv.province_name
    if (!name || name === '未知') continue
    districtSearch.search(name, (status, result) => {
      if (status !== 'complete' || !result.districtList || !result.districtList.length) return
      const boundaries = result.districtList[0].boundaries || []
      const polygon = new AMapRef.Polygon({
        path: boundaries,
        fillColor: colorOf(Number(pv.total) || 0, max),
        fillOpacity: 0.55,
        strokeColor: '#FFFFFF',
        strokeWeight: 1.2,
        strokeOpacity: 0.9,
        zIndex: 5,
        cursor: 'pointer',
        bubble: false,
        extData: pv
      })
      polygon.on('click', () => drill(pv, polygon))
      polygon.on('mouseover', () => polygon.setOptions({ fillOpacity: 0.75 }))
      polygon.on('mouseout', () => polygon.setOptions({ fillOpacity: 0.55 }))
      provincePolygons.push(polygon)
      map.add(polygon)
      if (!fitted && provincePolygons.length >= Math.min(3, props.provinces.length)) {
        map.setFitView(provincePolygons, false, [40, 40, 40, 40])
        fitted = true
      }
    })
  }
}

let drilledName = ''
function currentProvince() { return drilledName }

// 钻取省份：聚焦边界 + 展示该省车辆点
function drill(pv, polygon) {
  drilledName = pv.province_name
  drilled.value = true
  if (polygon) map.setFitView([polygon], false, [60, 60, 60, 60])
  renderMarkers(pv.province_name)
  emit('drill', pv)
}

function backToChina() {
  drilledName = ''
  drilled.value = false
  clearMarkers()
  if (provincePolygons.length) map.setFitView(provincePolygons, false, [40, 40, 40, 40])
  emit('drill', null)
}

function clearMarkers() {
  markers.forEach(m => map.remove(m))
  markers = []
  if (infoWindow) infoWindow.close()
}

function renderMarkers(provinceName) {
  if (!map || !AMapRef) return
  clearMarkers()
  const list = (props.vehicles || []).filter(v => (v.province_name || '未知') === provinceName)
  for (const v of list) {
    if (typeof v.lng !== 'number' || typeof v.lat !== 'number') continue
    const online = Number(v.online) === 1
    const marker = new AMapRef.CircleMarker({
      center: [v.lng, v.lat],
      radius: online ? 7 : 5,
      strokeColor: '#FFFFFF',
      strokeWeight: 1.5,
      fillColor: online ? '#2563EB' : '#A3A3A3',
      fillOpacity: online ? 0.9 : 0.6,
      zIndex: online ? 20 : 10,
      cursor: 'pointer',
      extData: v,
      bubble: false
    })
    marker.on('click', () => {
      emit('select', v)
      openInfo(v)
    })
    markers.push(marker)
    map.add(marker)
  }
}

function fmtTime(t) {
  if (!t) return '-'
  const d = new Date(t)
  return isNaN(d.getTime()) ? String(t) : d.toLocaleString('zh-CN', { hour12: false })
}

function openInfo(v) {
  if (!infoWindow || !map) return
  const online = Number(v.online) === 1
  const loc = [v.province_name, v.city_name, v.district_name].filter(Boolean).join(' / ')
  const html = '<div style="font-size:12px;line-height:1.9;min-width:180px;color:#0F1B2D">'
    + '<div style="font-weight:700;font-size:13px">' + (v.plate_no || v.vin) + '</div>'
    + '<div style="color:#A1A1A1">VIN: ' + v.vin + '</div>'
    + (v.car_brand_name ? '<div>' + v.car_brand_name + (v.car_model_name ? ' / ' + v.car_model_name : '') + '</div>' : '')
    + (loc ? '<div>' + loc + '</div>' : '')
    + '<div>最后上报：' + fmtTime(v.report_time) + '</div>'
    + '<div>状态：<span style="font-weight:600;color:' + (online ? '#22C55E' : '#A1A1A1') + '">'
    + (online ? '在线' : '离线') + '</span></div>'
    + '</div>'
  infoWindow.setContent(html)
  infoWindow.open(map, [v.lng, v.lat])
}

// 对外暴露：供右侧排行点击钻取
function drillByName(name) {
  const pv = props.provinces.find(p => p.province_name === name)
  if (pv) drill(pv, provincePolygons.find(pg => pg.getExtData && pg.getExtData().province_name === name))
}
defineExpose({ drillByName, backToChina })
</script>

<style scoped>
.amap-wrap { position: absolute; inset: 0; }
.amap-container { width: 100%; height: 100%; border-radius: var(--card-radius); }
.back-chip {
  position: absolute; left: 14px; top: 14px; z-index: 6;
  background: rgba(255, 255, 255, 0.92); backdrop-filter: blur(6px);
  border-radius: 18px; padding: 7px 14px; cursor: pointer;
  font-size: 12px; color: var(--primary); font-weight: 600;
  box-shadow: var(--card-shadow);
}
.back-chip:hover { background: var(--primary-light); }
.map-error {
  position: absolute; inset: 0; display: flex; align-items: center; justify-content: center;
  color: var(--danger); font-size: 13px;
}
</style>

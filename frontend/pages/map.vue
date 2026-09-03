<template>
  <!-- 设备孪生地图：深色数字孪生驾驶仓（地图为核心 + 筛选联动 + 实时告警） -->
  <AppShell title="设备孪生地图" theme="green">
    <div class="twin">
      <FilterBar
        :filters="filters" :provinces="provinceOptions" :brands="brandOptions"
        :last-update="lastUpdate"
        @change="onFilterChange" @refresh="load"
      />

      <!-- 上半：地图舞台 + KPI 列 -->
      <section class="band">
        <div class="stage">
          <ClientOnly>
            <VehicleMap
              ref="mapRef" :vehicles="mapVehicles" :provinces="provinces"
              @drill="onDrill" @select-vehicle="onSelectVehicle"
            />
          </ClientOnly>

          <!-- 搜索框：车牌 / VIN -->
          <div class="stage-search">
            <input
              v-model="searchKw" type="text" :placeholder="'搜索车牌 / VIN'"
              @input="onSearchInput" @focus="searchFocus = true"
            />
            <div v-if="searchFocus && searchKw && searchResults.length" class="search-drop">
              <div v-for="r in searchResults" :key="r.vin" class="search-item" @click="pickSearch(r)">
                <b>{{ r.plate_no || r.vin }}</b>
                <span>{{ [r.province_name, r.city_name].filter(Boolean).join(' ') || '位置未知' }}</span>
              </div>
            </div>
            <div v-else-if="searchFocus && searchKw && searchDone && !searchResults.length" class="search-drop">
              <div class="search-item none">暂无匹配车辆</div>
            </div>
          </div>

          <!-- 主文案 -->
          <div class="hero">
            <h1><span class="thin">让每一辆车</span><br>更智能、更安全</h1>
            <p>基于 AI 与大数据的车辆智能管理平台<br>实时感知 · 智能分析 · 高效运营</p>
          </div>

          <!-- 选中车辆详情卡 -->
          <div v-if="selected" class="veh-card">
            <button class="veh-close" @click="selected = null">×</button>
            <div class="veh-plate">{{ selected.plate_no || selected.vin }}</div>
            <div class="veh-line">VIN: {{ selected.vin }}</div>
            <div class="veh-line">品牌：{{ selected.car_brand_name || '-' }} {{ selected.car_model_name || '' }}</div>
            <div class="veh-line">位置：{{ [selected.province_name, selected.city_name, selected.district_name].filter(Boolean).join(' / ') || '未知' }}</div>
            <div class="veh-line">里程：{{ Math.round(Number(selected.mileage) || 0) }} km · 状态：{{ tierText(selected) }}</div>
            <div class="veh-line">最后上报：{{ fmtTime(selected.report_time) }}</div>
          </div>
        </div>

        <!-- KPI 列：四张可点击 -->
        <div class="kpis">
          <TwinKpiCard
            :title="'实时在线车辆'" en="Realtime Online" accent="teal"
            :value="kpi.online" :unit="'辆'" to="/chat"
          >在线率 <b class="up">{{ kpi.rate }}%</b></TwinKpiCard>
          <TwinKpiCard
            :title="'累计行驶里程'" en="Total Mileage" accent="cyan"
            :value="kpi.mileageText" unit="km" to="/console/stats"
          >覆盖 {{ kpi.provinces }} 省 {{ kpi.cities }} 市</TwinKpiCard>
          <TwinKpiCard
            :title="'设备总数'" en="Total Devices" accent="amber"
            :value="kpi.total" :unit="'台'" clickable @press="clearFilters"
          >停车 {{ kpi.park }} · 离线 {{ kpi.lost }}</TwinKpiCard>
          <TwinKpiCard
            :title="'实时告警'" en="Active Alerts" accent="red"
            :value="kpi.alertTotal" :unit="'条'" to="/anomaly"
          >严重告警 <b class="down">{{ kpi.alertSevere }}</b></TwinKpiCard>
        </div>
      </section>

      <!-- 中部：四块图表 -->
      <section class="charts">
        <div class="card">
          <h3>设备状态</h3>
          <StatusDonut :segments="statusSegs" />
        </div>
        <div class="card">
          <h3>告警趋势 <span class="h3-sub">近24小时</span></h3>
          <ClientOnly><AlertTrendChart :trend="trend" /></ClientOnly>
        </div>
        <div class="card">
          <h3>品牌分布</h3>
          <RankBars :items="brandItems" accent="cyan" />
        </div>
        <div class="card">
          <h3>区域 TOP5</h3>
          <RankBars :items="regionItems" clickable @pick="onRegionPick" />
        </div>
      </section>

      <!-- 下部：实时告警 + 快捷操作 -->
      <section class="alerts-row">
        <div class="card alerts-card">
          <h3>实时告警 <span class="h3-sub">近24小时最新 20 条</span></h3>
          <AlertTable :alerts="filteredAlerts" @locate="onLocate" />
        </div>
        <div class="card qa-card">
          <h3>快捷操作</h3>
          <QuickActions @export="exportCsv" @refresh="load" />
        </div>
      </section>
    </div>
  </AppShell>
</template>

<script setup>
const api = useApi()
const mapRef = ref(null)
const vehicles = ref([])
const provinces = ref([])
const brands = ref([])
const overview = ref({})
const alerts = ref([])
const filters = ref({ province: '', brand: '', status: '' })
const lastUpdate = ref('')
const selected = ref(null)
const searchKw = ref('')
const searchResults = ref([])
const searchFocus = ref(false)
const searchDone = ref(false)
let searchTimer = null

// 拉取驾驶仓全部数据（30s 轮询）
async function load() {
  const [vsR, psR, ovR, alR] = await Promise.allSettled([
    api.get('/ag-ui/map/vehicles'),
    api.get('/ag-ui/map/province-stats'),
    api.get('/ag-ui/map/overview'),
    api.get('/ag-ui/map/alerts?limit=20')
  ])
  if (vsR.status === 'fulfilled') vehicles.value = Array.isArray(vsR.value) ? vsR.value : []
  if (psR.status === 'fulfilled' && psR.value) {
    provinces.value = Array.isArray(psR.value.provinces) ? psR.value.provinces : []
    brands.value = Array.isArray(psR.value.brands) ? psR.value.brands : []
  }
  if (ovR.status === 'fulfilled' && ovR.value) overview.value = ovR.value
  if (alR.status === 'fulfilled') alerts.value = Array.isArray(alR.value) ? alR.value : []
  if (vsR.status === 'rejected' && psR.status === 'rejected' && ovR.status === 'rejected') {
    throw new Error('all map endpoints failed')
  }
  lastUpdate.value = new Date().toLocaleTimeString('zh-CN', { hour12: false })
}

// 在线分层：0=运行 1=停车 2=离线
function tierOf(v) {
  const t = Number(v.online_level)
  return isNaN(t) ? (Number(v.online) === 1 ? 0 : 2) : t
}
function tierText(v) {
  const t = tierOf(v)
  return t === 0 ? '在线' : (t === 1 ? '停车' : '离线')
}
function fmtTime(t) {
  return typeof t === 'string' && t.length >= 19 ? t.slice(0, 19).replace('T', ' ') : (t || '-')
}

// 筛选联动：区域 / 品牌 / 状态
const filteredVehicles = computed(() => vehicles.value.filter(v => {
  if (filters.value.province && (v.province_name || '') !== filters.value.province) return false
  if (filters.value.brand && (v.car_brand_name || '') !== filters.value.brand) return false
  if (filters.value.status !== '' && tierOf(v) !== Number(filters.value.status)) return false
  return true
}))
const mapVehicles = computed(() => filteredVehicles.value.slice(0, 800))

const filteredAlerts = computed(() => {
  const vins = new Set(filteredVehicles.value.map(v => v.vin))
  return alerts.value.filter(a => vins.has(a.vin))
})

const provinceOptions = computed(() =>
  provinces.value.map(p => p.province_name).filter(n => n && n !== '未知'))
const brandOptions = computed(() =>
  brands.value.map(b => b.brand).filter(n => n && n !== '未知' && n !== 'N/A'))

// KPI：基于筛选结果实时计算（告警用 overview 全局口径）
const kpi = computed(() => {
  const list = filteredVehicles.value
  const run = list.filter(v => tierOf(v) === 0).length
  const park = list.filter(v => tierOf(v) === 1).length
  const lost = list.filter(v => tierOf(v) === 2).length
  const total = list.length
  const mileage = list.reduce((s, v) => s + (Number(v.mileage) || 0), 0)
  return {
    online: run, park, lost, total,
    rate: total ? Math.round((run / total) * 100) : 0,
    mileageText: mileage >= 10000 ? (mileage / 10000).toFixed(1) + ' 万' : Math.round(mileage),
    provinces: new Set(list.map(v => v.province_name).filter(Boolean)).size,
    cities: new Set(list.map(v => v.city_name).filter(Boolean)).size,
    alertTotal: Number(overview.value.alertTotal) || 0,
    alertSevere: Number(overview.value.alertSevere) || 0
  }
})

const statusSegs = computed(() => [
  { name: '运行', value: kpi.value.online, color: '#2DD4BF' },
  { name: '停车', value: kpi.value.park, color: '#38BDF8' },
  { name: '离线', value: kpi.value.lost, color: '#F5B544' }
])

const trend = computed(() => Array.isArray(overview.value.alertTrend) ? overview.value.alertTrend : [])

const brandItems = computed(() => {
  const counter = {}
  for (const v of filteredVehicles.value) {
    const b = v.car_brand_name || '未知'
    counter[b] = (counter[b] || 0) + 1
  }
  return Object.entries(counter).sort((a, b) => b[1] - a[1]).slice(0, 5)
    .map(([name, value]) => ({ name, value }))
})

const regionItems = computed(() => {
  const counter = {}
  for (const v of filteredVehicles.value) {
    const p = v.province_name || '未知'
    if (!counter[p]) counter[p] = { total: 0, online: 0 }
    counter[p].total++
    if (tierOf(v) === 0) counter[p].online++
  }
  return Object.entries(counter).sort((a, b) => b[1].total - a[1].total).slice(0, 5)
    .map(([name, x]) => ({ name, value: x.total, sub: '在线 ' + x.online }))
})

function onFilterChange(f) {
  filters.value = f
  if (!f.province && mapRef.value) mapRef.value.backToChina()
}
function clearFilters() {
  filters.value = { province: '', brand: '', status: '' }
  if (mapRef.value) mapRef.value.backToChina()
}
function onDrill(d) {
  if (d && d.level === 'province') filters.value = { ...filters.value, province: d.name }
  else filters.value = { ...filters.value, province: '' }
}
function onRegionPick(it) {
  if (!it || !it.name || it.name === '未知') return
  filters.value = { ...filters.value, province: it.name }
  if (mapRef.value) mapRef.value.drillByName(it.name)
}
function onSelectVehicle(v) {
  selected.value = v
}

// 搜索：车牌 / VIN 模糊，300ms 防抖
function onSearchInput() {
  searchDone.value = false
  if (searchTimer) clearTimeout(searchTimer)
  const kw = searchKw.value.trim()
  if (kw.length < 2) { searchResults.value = []; return }
  searchTimer = setTimeout(async () => {
    try {
      const r = await api.get('/ag-ui/map/search?keyword=' + encodeURIComponent(kw))
      searchResults.value = Array.isArray(r) ? r : []
    } catch (e) {
      searchResults.value = []
    }
    searchDone.value = true
  }, 300)
}
function pickSearch(r) {
  searchFocus.value = false
  searchKw.value = r.plate_no || r.vin
  selected.value = r
  if (mapRef.value) mapRef.value.panToVehicle(r)
}

// 告警定位：地图飞到对应车辆
function onLocate(a) {
  const v = vehicles.value.find(x => x.vin === a.vin)
  if (v) {
    selected.value = v
    if (mapRef.value) mapRef.value.panToVehicle(v)
  }
}

// 导出当前筛选车辆 CSV（带 BOM 兼容 Excel）
function exportCsv() {
  const head = ['车牌', 'VIN', '省份', '城市', '品牌', '车型', '里程', '状态', '最后上报']
  const lines = [head.join(',')]
  for (const v of filteredVehicles.value) {
    lines.push([
      v.plate_no || '', v.vin || '', v.province_name || '', v.city_name || '',
      v.car_brand_name || '', v.car_model_name || '',
      Math.round(Number(v.mileage) || 0), tierText(v), fmtTime(v.report_time)
    ].join(','))
  }
  const blob = new Blob(['﻿' + lines.join(String.fromCharCode(10))], { type: 'text/csv;charset=utf-8' })
  const a = document.createElement('a')
  a.href = URL.createObjectURL(blob)
  a.download = 'vehicles-' + new Date().toISOString().slice(0, 10) + '.csv'
  a.click()
  URL.revokeObjectURL(a.href)
}

onMounted(load)
usePolling(load, 30000)
</script>

<style scoped>
/* ===== 深色孪生设计令牌（页面局部生效） ===== */
.twin {
  --panel: rgba(255, 255, 255, .74);
  --line: rgba(14, 138, 138, .15);
  display: flex; flex-direction: column; gap: 14px; min-height: calc(100vh - 140px);
}

/* ===== 地图舞台 + KPI ===== */
.band { display: grid; grid-template-columns: minmax(0, 1fr) 240px; gap: 14px; min-height: 460px; }
.stage {
  position: relative; border-radius: 16px; overflow: hidden; min-height: 460px;
  border: 1px solid var(--line);
  background:
    radial-gradient(900px 600px at 70% 20%, rgba(14, 138, 138, .07) 0%, transparent 65%),
    radial-gradient(700px 500px at 20% 85%, rgba(43, 191, 175, .06) 0%, transparent 70%),
    linear-gradient(165deg, #EDF6F3 0%, #E2EFEA 60%, #D9EAE4 100%);
}
.stage::before {
  content: ''; position: absolute; inset: 0; pointer-events: none; z-index: 1;
  background-image:
    linear-gradient(rgba(14, 138, 138, .05) 1px, transparent 1px),
    linear-gradient(90deg, rgba(14, 138, 138, .05) 1px, transparent 1px);
  background-size: 44px 44px;
}
.kpis { display: flex; flex-direction: column; gap: 14px; }

/* ===== 搜索 ===== */
.stage-search { position: absolute; left: 50%; transform: translateX(-50%); top: 14px; z-index: 7; width: min(320px, 46%); }
.stage-search input {
  width: 100%; font: inherit; font-size: 12.5px; color: #12403E; outline: none;
  background: rgba(255, 255, 255, .86); border: 1px solid rgba(14, 138, 138, .22);
  border-radius: 10px; padding: 8px 14px; backdrop-filter: blur(8px);
}
.stage-search input::placeholder { color: #8AA6A2; }
.stage-search input:focus { border-color: rgba(14, 138, 138, .55); }
.search-drop {
  margin-top: 6px; background: rgba(255, 255, 255, .96); border: 1px solid rgba(14, 138, 138, .18);
  border-radius: 10px; overflow: hidden; max-height: 260px; overflow-y: auto;
}
.search-item { display: flex; align-items: center; gap: 10px; padding: 8px 14px; cursor: pointer; font-size: 12px; }
.search-item:hover { background: rgba(14, 138, 138, .08); }
.search-item b { color: #12403E; font-variant-numeric: tabular-nums; }
.search-item span { color: #5A7A76; overflow: hidden; text-overflow: ellipsis; white-space: nowrap; }
.search-item.none { color: #8AA6A2; cursor: default; }

/* ===== 主文案 ===== */
.hero { position: absolute; left: 24px; bottom: 22px; z-index: 3; pointer-events: none; }
.hero h1 { font-size: clamp(22px, 2vw, 32px); line-height: 1.28; letter-spacing: -.01em; font-weight: 800; color: #12403E; margin: 0; }
.hero h1 .thin { font-weight: 400; color: #4A7672; }
.hero p { margin: 10px 0 0; font-size: 12px; line-height: 1.8; color: #5A7A76; }

/* ===== 车辆详情卡 ===== */
.veh-card {
  position: absolute; right: 14px; bottom: 14px; z-index: 7; width: 240px;
  background: rgba(255, 255, 255, .9); border: 1px solid rgba(14, 138, 138, .28);
  border-radius: 12px; padding: 12px 14px; backdrop-filter: blur(10px);
}
.veh-plate { font-size: 15px; font-weight: 700; color: #0E8A8A; margin-bottom: 6px; font-variant-numeric: tabular-nums; }
.veh-line { font-size: 11.5px; color: #4A7672; line-height: 1.75; }
.veh-close {
  position: absolute; right: 8px; top: 6px; background: none; border: 0; color: #5A7A76;
  font-size: 16px; cursor: pointer;
}
.veh-close:hover { color: #12403E; }

/* ===== 图表区 / 告警区 ===== */
.charts { display: grid; grid-template-columns: 1fr 1.3fr 1fr 1fr; gap: 14px; }
.alerts-row { display: grid; grid-template-columns: minmax(0, 1fr) 260px; gap: 14px; }
.card {
  background: var(--panel); border: 1px solid var(--line); border-radius: 14px;
  padding: 14px 16px; backdrop-filter: blur(10px); min-width: 0;
}
.card h3 { font-size: 12.5px; font-weight: 600; color: #4A7672; letter-spacing: .02em; margin: 0 0 12px; }
.h3-sub { font-size: 10.5px; color: #8AA6A2; font-weight: 400; margin-left: 6px; }
.up { color: #0E8A8A; }
.down { color: #F87171; }

/* ===== 响应式 ===== */
@media (max-width: 1400px) {
  .charts { grid-template-columns: 1fr 1fr; }
}
@media (max-width: 1080px) {
  .band, .alerts-row { grid-template-columns: 1fr; }
  .kpis { flex-direction: row; flex-wrap: wrap; }
  .kpis > * { flex: 1 1 200px; }
}
@media (max-width: 680px) {
  .charts { grid-template-columns: 1fr; }
}
</style>

<template>
  <!-- 评估指标（借鉴 Office_Agent L1 免费层：日志推导、零 token 成本）
       口径：run_audit（run 维度）+ feedback（用户反馈），全量实时聚合 -->
  <AppShell title="Agent 评估指标">
    <div class="metrics-page">
      <!-- 告警条：失败/拒答超标时出现（对标 Office_Agent 的 DENY 弹条） -->
      <div v-if="alertText" class="alert-bar" role="alert">
        <span class="ab-icon">⚠</span>
        <span>{{ alertText }}</span>
        <button class="ab-link" type="button" @click="gotoStats">去数据统计看明细 →</button>
      </div>

      <!-- KPI 卡（超标自动红框） -->
      <div class="kpi-grid">
        <div v-for="c in kpiCards" :key="c.label" class="kpi-card" :class="{ warn: c.warn }">
          <div class="kc-head">
            <span class="kc-label">{{ c.label }}</span>
            <span class="kc-icon">{{ c.icon }}</span>
          </div>
          <div class="kc-value">{{ c.value }}</div>
          <div v-if="c.hint" class="kc-hint">{{ c.hint }}</div>
        </div>
      </div>

      <!-- 按天趋势：runs 总量柱 + failed/refused 叠加（CSS 柱，无需图表库） -->
      <PanelCard title="运行趋势">
        <template #actions>
          <span class="range-row">
            <button
              v-for="d in [7, 14, 30]"
              :key="d"
              class="range-btn"
              :class="{ on: days === d }"
              type="button"
              @click="setDays(d)"
            >{{ d }} 天</button>
          </span>
        </template>
        <div v-if="!daily.length" class="empty">窗口内暂无运行数据</div>
        <div v-else class="trend">
          <div class="trend-bars">
            <div v-for="d in dailyFilled" :key="d.day" class="tb-col" :title="d.day + '：' + d.runs + ' 次（失败 ' + (d.failed || 0) + ' · 拒答 ' + (d.refused || 0) + '）'">
              <div class="tb-stack">
                <div v-if="d.refused" class="tb-seg refused" :style="{ height: segH(d.refused) }"></div>
                <div v-if="d.failed" class="tb-seg failed" :style="{ height: segH(d.failed) }"></div>
                <div class="tb-seg ok" :style="{ height: segH(d.ok || 0) }"></div>
              </div>
              <span class="tb-day">{{ d.day.slice(5) }}</span>
            </div>
          </div>
          <div class="trend-legend">
            <span class="lg ok">成功</span><span class="lg failed">失败</span><span class="lg refused">拒答</span>
          </div>
        </div>
      </PanelCard>

      <div class="two-col">
        <!-- 路由分布：cross 占比 = 路由兜底率（本页特色指标） -->
        <PanelCard title="路由分布（fleet_copilot）">
          <template #actions><span class="hint">兜底率 = cross 占比</span></template>
          <div v-if="!byRoute.length" class="empty">暂无 copilot 运行</div>
          <table v-else class="m-table">
            <thead><tr><th>路由</th><th>次数</th><th>成功率</th><th>平均耗时</th><th>占比</th></tr></thead>
            <tbody>
              <tr v-for="r in byRoute" :key="r.route">
                <td class="mono">{{ routeCn(r.route) }}</td>
                <td>{{ r.runs }}</td>
                <td :class="{ bad: pct(r.ok, r.runs) < 80 }">{{ pct(r.ok, r.runs) }}%</td>
                <td>{{ r.avg_ms }}ms</td>
                <td>{{ pct(r.runs, routeTotal) }}%</td>
              </tr>
            </tbody>
          </table>
        </PanelCard>

        <!-- 能力分桶：错误率定位具体 capability -->
        <PanelCard title="能力调用 Top15">
          <template #actions><span class="hint">错误率高 = 先查该能力 SQL/schema</span></template>
          <div v-if="!byCapability.length" class="empty">暂无能力调用</div>
          <table v-else class="m-table">
            <thead><tr><th>能力</th><th>次数</th><th>错误率</th><th>平均耗时</th><th>平均行数</th></tr></thead>
            <tbody>
              <tr v-for="c in byCapability" :key="c.capability_id">
                <td class="mono">{{ c.capability_id }}</td>
                <td>{{ c.runs }}</td>
                <td :class="{ bad: errRate(c) > 10 }">{{ errRate(c) }}%</td>
                <td>{{ c.avg_ms }}ms</td>
                <td>{{ c.avg_rows }}</td>
              </tr>
            </tbody>
          </table>
        </PanelCard>
      </div>

      <!-- 错误码分布 -->
      <PanelCard title="错误码分布">
        <div v-if="!topErrors.length" class="empty">窗口内无错误 🎉</div>
        <div v-else class="err-row-list">
          <div v-for="e in topErrors" :key="e.error_code" class="err-row">
            <span class="mono err-code">{{ e.error_code }}</span>
            <div class="err-track"><div class="err-bar" :style="{ width: errPct(e.cnt) + '%' }"></div></div>
            <span class="err-cnt">{{ e.cnt }} 次</span>
          </div>
        </div>
      </PanelCard>

      <!-- 指标口径（对标 Office_Agent renderDefs：指标 → 动作映射） -->
      <PanelCard title="指标口径与动作映射">
        <div class="defs">
          <div v-for="d in defs" :key="d.k" class="def-row">
            <span class="def-k">{{ d.k }}</span>
            <span class="def-v">{{ d.v }}</span>
          </div>
          <div class="footnote">数据窗口 {{ days }} 天 · 全量实时聚合，零 token 成本 · 来源：run_audit + feedback 表</div>
        </div>
      </PanelCard>
    </div>
  </AppShell>
</template>

<script setup>
const api = useApi()

const days = ref(7)
const overview = ref({})
const byRoute = ref([])
const byCapability = ref([])
const topErrors = ref([])
const daily = ref([])

const routeTotal = computed(() => byRoute.value.reduce((s, r) => s + (r.runs || 0), 0))
const maxErrCnt = computed(() => Math.max(1, ...topErrors.value.map(e => e.cnt || 0)))
const maxDayRuns = computed(() => Math.max(1, ...daily.value.map(d => d.runs || 0)))

function pct(part, total) {
  const p = Number(part), t = Number(total)
  return (t > 0 && !isNaN(p)) ? Math.round((p / t) * 1000) / 10 : 0
}
function errRate(c) {
  return pct((c.runs || 0) - (c.ok || 0), c.runs)
}
function errPct(cnt) {
  return Math.max(6, Math.round((cnt / maxErrCnt.value) * 100))
}
function segH(v) {
  return Math.max(3, Math.round(((v || 0) / maxDayRuns.value) * 96)) + 'px'
}
function routeCn(route) {
  const key = String(route || '').replace('copilot:', '')
  const map = {
    vehicle: '车辆专家', alarm: '告警专家', fault: '故障专家', mileage: '里程专家',
    anomaly: '异常探查', cross: '跨域兜底', cross_fb: '跨域兜底·降级', meta: '平台信息', chat: '闲聊引导', forced: '指定工具'
  }
  return (map[key] || key) + ' · ' + key
}

// 告警条：失败/拒答超标提示（对标 Office_Agent DENY 弹条）
const alertText = computed(() => {
  const ov = overview.value
  if (!ov || !ov.total) return ''
  const failRate = pct(ov.failed, ov.total)
  const refuseRate = pct(ov.refused, ov.total)
  if (failRate > 15) return '失败率 ' + failRate + '% 偏高，建议看下方错误码分布定位链路'
  if (refuseRate > 15) return '拒答率 ' + refuseRate + '% 偏高（其中 understand 阶段 ' + (ov.refused_understand || 0) + ' 次 = 能力覆盖不足）'
  return ''
})

const kpiCards = computed(() => {
  const ov = overview.value || {}
  return [
    { label: '回答总数', value: ov.total || 0, icon: '▦' },
    { label: '成功率', value: pct(ov.success, ov.total) + '%', icon: '✓', warn: ov.total && pct(ov.success, ov.total) < 85 },
    { label: '失败率', value: pct(ov.failed, ov.total) + '%', icon: '✕', warn: pct(ov.failed, ov.total) > 15 },
    { label: '拒答率', value: pct(ov.refused, ov.total) + '%', icon: '⊘', warn: pct(ov.refused, ov.total) > 15, hint: (ov.refused_understand || 0) + ' 次因能力未覆盖' },
    { label: '平均耗时', value: (ov.avg_ms || 0) + 'ms', icon: '⏱' },
    { label: '平均返回行数', value: ov.avg_rows || 0, icon: '≡' },
    { label: '点赞率', value: ov.feedback_total ? pct(ov.feedback_up, ov.feedback_total) + '%' : '—', icon: '👍' },
    { label: '路由兜底率', value: routeTotal.value ? pct(crossRuns, routeTotal.value) + '%' : '—', icon: '⤵', warn: routeTotal.value && pct(crossRuns, routeTotal.value) > 40, hint: 'cross 占比（路由超时/额度）' }
  ]
})
const crossRuns = computed(() => {
  const c = byRoute.value.find(r => r.route === 'copilot:cross')
  return c ? c.runs : 0
})

// 趋势补零填日（对标 Office_Agent：窗口内没数据的天也画出零柱）
const dailyFilled = computed(() => {
  const byDay = {}
  daily.value.forEach(d => { byDay[String(d.day).slice(0, 10)] = d })
  const out = []
  const today = new Date()
  for (let i = days.value - 1; i >= 0; i--) {
    const dt = new Date(today.getTime() - i * 86400000)
    const iso = dt.getFullYear() + '-' + String(dt.getMonth() + 1).padStart(2, '0') + '-' + String(dt.getDate()).padStart(2, '0')
    out.push(byDay[iso] || { day: iso, runs: 0, ok: 0, failed: 0, refused: 0 })
  }
  return out
})

// 指标口径（每个指标配一个「高了该做什么」）
const defs = [
  { k: '失败率', v: '超 15% → 看错误码分布；LLM_TIMEOUT 多为 GLM 额度/网络；SCHEMA_INVALID 多为参数校验' },
  { k: '拒答率', v: '超 15% → understand 阶段占比高说明能力覆盖不足，去 Capability 管理补能力' },
  { k: '路由兜底率', v: 'cross 占比 > 40% → 路由常超时/降级（检查 GLM 额度与 extract 超时配置），专家命中率低' },
  { k: '平均耗时', v: '偏高 → 联动数据统计页慢查询榜，定位 SQL 或模型耗时' },
  { k: '点赞率', v: '唯一的用户满意度信号；低分结合错误分布看是「答不出」还是「答得慢」' },
  { k: '能力错误率', v: '单个能力错误率 > 10% → 优先查该能力的 SQL 模板与参数 schema' }
]

async function load() {
  try {
    const data = await api.get('/admin/stats/agent-eval?days=' + days.value)
    overview.value = data.overview || {}
    byRoute.value = data.byRoute || []
    byCapability.value = data.byCapability || []
    topErrors.value = data.topErrors || []
    daily.value = data.daily || []
  } catch (e) { /* 保持空态 */ }
}

function setDays(d) {
  days.value = d
  load()
}

function gotoStats() {
  navigateTo('/console/stats?tab=audit')
}

onMounted(load)
</script>

<style scoped>
.metrics-page { display: flex; flex-direction: column; gap: 12px; }
/* 告警条 */
.alert-bar {
  display: flex; align-items: center; gap: 10px;
  padding: 10px 16px; border-radius: 12px;
  background: #FEF3C7; border: 1px solid #FDE68A; color: #92400E;
  font-size: 13px;
}
.ab-icon { font-size: 15px; }
.ab-link {
  margin-left: auto; border: none; background: none; cursor: pointer;
  color: #92400E; font: inherit; font-size: 12px; text-decoration: underline;
}
/* KPI 卡 */
.kpi-grid { display: grid; grid-template-columns: repeat(4, minmax(0, 1fr)); gap: 12px; }
.kpi-card {
  background: #fff; border-radius: 14px; padding: 16px 18px;
  border: 1px solid var(--border-light, #F3F4F6);
  box-shadow: 0 2px 8px rgba(17, 24, 39, .05);
}
.kpi-card.warn { border-color: #F0C8C4; }
.kc-head { display: flex; align-items: center; justify-content: space-between; margin-bottom: 8px; }
.kc-label { font-size: 11px; color: var(--text-3); letter-spacing: .04em; }
.kc-icon { font-size: 13px; color: var(--text-3); opacity: .7; }
.kc-value { font-size: 25px; font-weight: 700; color: var(--text-1); font-variant-numeric: tabular-nums; }
.kpi-card.warn .kc-value { color: #B91C1C; }
.kc-hint { font-size: 10.5px; color: var(--text-3); margin-top: 4px; }
/* 趋势 */
.range-row { display: inline-flex; gap: 4px; }
.range-btn {
  height: 22px; padding: 0 10px; border-radius: 999px; cursor: pointer;
  border: 1px solid var(--border-default); background: #fff; color: var(--text-3); font: inherit; font-size: 11px;
}
.range-btn.on { background: var(--primary-light); color: var(--primary); font-weight: 600; }
.trend-bars { display: flex; align-items: flex-end; gap: 6px; height: 130px; padding: 4px 2px 0; }
.tb-col { flex: 1; display: flex; flex-direction: column; align-items: center; gap: 4px; min-width: 0; }
.tb-stack { display: flex; flex-direction: column-reverse; width: 100%; max-width: 34px; }
.tb-seg { width: 100%; border-radius: 3px 3px 0 0; }
.tb-seg.ok { background: #10B981; }
.tb-seg.failed { background: #EF4444; }
.tb-seg.refused { background: #F59E0B; }
.tb-day { font-size: 9.5px; color: var(--text-3); white-space: nowrap; }
.trend-legend { display: flex; gap: 12px; margin-top: 8px; }
.lg { font-size: 11px; color: var(--text-3); display: inline-flex; align-items: center; gap: 4px; }
.lg::before { content: ''; width: 8px; height: 8px; border-radius: 2px; display: inline-block; }
.lg.ok::before { background: #10B981; }
.lg.failed::before { background: #EF4444; }
.lg.refused::before { background: #F59E0B; }
/* 双列表 */
.two-col { display: grid; grid-template-columns: repeat(2, minmax(0, 1fr)); gap: 12px; }
.m-table { width: 100%; border-collapse: collapse; font-size: 12.5px; }
.m-table th {
  text-align: left; padding: 7px 10px; color: var(--text-3); font-weight: 500; font-size: 11.5px;
  border-bottom: 1px solid var(--border-default);
}
.m-table td { padding: 7px 10px; border-bottom: 1px solid var(--border-light); color: var(--text-1); }
.m-table td.bad { color: #B91C1C; font-weight: 600; }
.mono { font-family: "SF Mono", Consolas, monospace; font-size: 11.5px; }
.hint { font-size: 11px; color: var(--text-3); }
.empty { color: var(--text-3); font-size: 12px; padding: 18px 0; text-align: center; }
/* 错误码 */
.err-row-list { display: flex; flex-direction: column; gap: 8px; }
.err-row { display: flex; align-items: center; gap: 12px; font-size: 12px; }
.err-code { color: #B91C1C; flex: 0 0 150px; }
.err-track { flex: 1; height: 8px; border-radius: 4px; background: var(--field); overflow: hidden; }
.err-bar { height: 100%; border-radius: 4px; background: linear-gradient(90deg, #F87171, #EF4444); }
.err-cnt { color: var(--text-3); flex: 0 0 52px; text-align: right; font-variant-numeric: tabular-nums; }
/* 口径 */
.defs { display: flex; flex-direction: column; gap: 8px; }
.def-row { display: flex; gap: 14px; font-size: 12.5px; line-height: 1.7; }
.def-k { flex: 0 0 92px; color: var(--text-2); font-weight: 600; }
.def-v { color: var(--text-2); }
.footnote { margin-top: 6px; font-size: 11px; color: var(--text-3); }

@media (max-width: 1180px) {
  .two-col { grid-template-columns: 1fr; }
}
@media (max-width: 880px) {
  .kpi-grid { grid-template-columns: repeat(2, minmax(0, 1fr)); }
}
</style>

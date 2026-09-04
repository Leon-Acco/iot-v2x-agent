<template>
  <!-- 工具调用叙事：树形行 + 中文叙事 + 入参摘要，完成后折叠；失败行带重试入口 -->
  <div v-if="tools.length" class="tool-narrative">
    <div v-if="allDone && !hasError" class="tool-collapsed" @click="expanded = !expanded">
      <span class="done-icon">✓</span>
      已完成取证（{{ tools.length }} 步）
      <span class="toggle-hint">{{ expanded ? '收起' : '展开过程' }}</span>
    </div>
    <template v-if="!allDone || hasError || expanded">
      <div v-for="(t, i) in tools" :key="i" class="tool-row" @click="toggleDetail(i)">
        <span class="tree-prefix">{{ i === tools.length - 1 ? '└─' : '├─' }}</span>
        <span v-if="t.status === 'running'" class="spinner"></span>
        <span v-else-if="t.status === 'done'" class="done-icon">✓</span>
        <span v-else class="error-icon">✕</span>
        <span class="tool-name">{{ narrative(t) }}</span>
        <span v-if="t.summary" class="tool-summary">{{ t.summary }}</span>
        <button v-if="t.status === 'error'" class="retry-tool-btn" type="button" @click.stop="$emit('retry')">
          重试
        </button>
        <div v-if="detailIndex === i" class="tool-detail" @click.stop>
          <div class="detail-block">
            <div class="detail-label">调用工具：{{ toolCnName(t) }}</div>
            <div v-if="argsBrief(t)" class="detail-label">入参：{{ argsBrief(t) }}</div>
          </div>
          <div v-if="t.args" class="detail-block">
            <div class="detail-label">参数</div>
            <pre>{{ pretty(t.args) }}</pre>
          </div>
          <div v-if="t.result && t.result.stats && t.result.stats.sqlSnapshot" class="detail-block">
            <div class="detail-label">SQL</div>
            <pre>{{ t.result.stats.sqlSnapshot }}</pre>
          </div>
        </div>
      </div>
    </template>
  </div>
</template>

<script setup>
const props = defineProps({
  tools: { type: Array, default: () => [] }
})
const emit = defineEmits(['retry'])
const expanded = ref(false)
const detailIndex = ref(-1)

const allDone = computed(() =>
  props.tools.length > 0 && props.tools.every(t => t.status !== 'running')
)
const hasError = computed(() => props.tools.some(t => t.status === 'error'))

function toggleDetail(i) {
  detailIndex.value = detailIndex.value === i ? -1 : i
}

function pretty(obj) {
  try { return JSON.stringify(obj, null, 2) } catch (e) { return String(obj) }
}

// 工具名归一：bridge 发中文名；AgentScope 事件可能带英文 id（含 #seq 后缀）
const TOOL_CN = {
  query_vehicle_info: '车辆档案',
  list_offline_vehicles: '离线车辆清单',
  query_vehicle_location: '最后位置',
  count_alarms_by_type: '告警类型统计',
  list_alarms: '告警明细',
  compare_fleet_alarms: '车队告警对比',
  count_faults_by_part: '故障部位统计',
  list_faults: '故障明细',
  compare_fleet_mileage: '车队里程对比',
  query_mileage_daily: '每日里程趋势',
  query_charge_stats: '充电统计',
  generate_visualization: '生成可视化图表',
  calculate: '数值计算'
}

function rawName(t) {
  return String(t.name || '').split('#')[0]
}

function toolCnName(t) {
  const raw = rawName(t)
  return TOOL_CN[raw] || raw
}

// 入参摘要：车辆 / 时间 / 阈值等业务参数拼成一句话
function argsBrief(t) {
  const a = t.args && typeof t.args === 'object' ? t.args : {}
  const parts = []
  if (a.vehicle) parts.push('车辆 ' + a.vehicle)
  if (a.time_range_display) parts.push(a.time_range_display)
  if (a.hours != null) parts.push('超 ' + a.hours + ' 小时')
  if (a.active_only) parts.push('仅未恢复')
  if (a.limit) parts.push('前 ' + a.limit + ' 条')
  if (a.title) parts.push(String(a.title).slice(0, 20))
  return parts.join(' · ')
}

// 叙事行：中文能力名 + 入参摘要（对齐 Office_Agent 的自然语言叙事）
function narrative(t) {
  const brief = argsBrief(t)
  return brief ? toolCnName(t) + '（' + brief + '）' : toolCnName(t)
}
</script>

<style scoped>
.tool-narrative { margin: 8px 0; font-size: 13px; }
.tool-collapsed {
  display: inline-flex; align-items: center; gap: 6px;
  color: var(--text-3); cursor: pointer; padding: 4px 8px; border-radius: 6px;
}
.tool-collapsed:hover { background: var(--primary-light); }
.toggle-hint { font-size: 11px; color: var(--text-3); }
.tool-row {
  display: flex; align-items: center; flex-wrap: wrap; gap: 6px;
  padding: 3px 0; color: var(--text-2); cursor: pointer;
  font-size: 12.5px;
}
.tree-prefix { color: var(--line-strong); font-family: "SF Mono", Consolas, monospace; }
.tool-name { color: var(--text-1); font-weight: 500; }
.tool-summary { color: var(--text-3); font-variant-numeric: tabular-nums; }
.retry-tool-btn {
  height: 20px; padding: 0 9px; border-radius: 999px;
  border: 1px solid rgba(220, 38, 38, .4); background: rgba(220, 38, 38, .06);
  color: #dc2626; font: inherit; font-size: 11px; cursor: pointer;
}
.retry-tool-btn:hover { background: rgba(220, 38, 38, .14); }
.spinner {
  width: 11px; height: 11px; border-radius: 50%;
  border: 2px solid var(--line-strong); border-top-color: var(--primary);
  animation: spin .8s linear infinite;
}
@keyframes spin { to { transform: rotate(360deg); } }
.done-icon { color: var(--success); font-weight: 700; }
.error-icon { color: var(--danger); font-weight: 700; }
.tool-detail {
  width: 100%; margin: 4px 0 8px 22px; padding: 8px 10px;
  background: var(--field); border-radius: 8px; border: 1px solid var(--line);
}
.detail-block { margin-bottom: 4px; }
.detail-label { font-size: 11px; color: var(--text-3); margin-bottom: 4px; }
.tool-detail pre {
  margin: 0; font-size: 11px; white-space: pre-wrap; word-break: break-all;
  max-height: 180px; overflow-y: auto; color: var(--text-2);
}
</style>

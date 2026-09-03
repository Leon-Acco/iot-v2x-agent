<template>
  <!-- 实时告警表：等级/车牌/类型/位置/时间/状态 + 定位联动 -->
  <div class="alert-table">
    <div class="at-head at-row">
      <span class="c-level">等级</span>
      <span class="c-plate">车牌</span>
      <span class="c-type">告警类型</span>
      <span class="c-loc">位置</span>
      <span class="c-time">时间</span>
      <span class="c-status">状态</span>
      <span class="c-op">操作</span>
    </div>
    <div class="at-body">
      <div v-for="(a, i) in alerts" :key="i" class="at-row">
        <span class="c-level"><i class="lv-pill" :class="lvClass(a)">{{ lvText(a) }}</i></span>
        <span class="c-plate">{{ a.plate_no || a.vin }}</span>
        <span class="c-type">{{ typeName(a.alarm_type) }}</span>
        <span class="c-loc">{{ locOf(a) }}</span>
        <span class="c-time">{{ timeOf(a) }}</span>
        <span class="c-status"><i class="st-dot" :class="{ done: a.alarm_end_time }"></i>{{ a.alarm_end_time ? '已处理' : '待处理' }}</span>
        <span class="c-op">
          <button class="loc-btn" :disabled="!hasLoc(a)" @click="emit('locate', a)">定位</button>
        </span>
      </div>
      <div v-if="!alerts.length" class="at-empty">暂无告警</div>
    </div>
  </div>
</template>

<script setup>
const props = defineProps({
  alerts: { type: Array, default: () => [] }
})
const emit = defineEmits(['locate'])

// 告警类型码映射（JT/T 主流码，与 alarm_list 能力口径一致）
const TYPE_MAP = {
  1001: '前向碰撞报警',
  1002: '车道偏离报警',
  1003: '车距过近报警',
  1004: '行人碰撞报警',
  1005: '超速报警',
  1006: '疲劳驾驶报警',
  1007: '接打电话报警',
  1008: '抽烟报警',
  1009: '分神驾驶报警',
  1010: '驾驶员异常报警',
  1011: '双手脱离方向盘'
}
function typeName(t) {
  return TYPE_MAP[Number(t)] || ('告警类型 ' + t)
}
function lvClass(a) {
  const r = Number(a.risk_level) || 0
  return r >= 2 ? 'sev' : (r === 1 ? 'mid' : 'low')
}
function lvText(a) {
  const r = Number(a.risk_level) || 0
  return r >= 2 ? '严重' : (r === 1 ? '一般' : '提示')
}
function locOf(a) {
  return [a.province_name, a.city_name].filter(Boolean).join(' ') || '暂无位置'
}
function timeOf(a) {
  const t = a.alarm_start_time
  return typeof t === 'string' && t.length >= 16 ? t.slice(5, 16).replace('T', ' ') : (t || '-')
}
function hasLoc(a) {
  return typeof a.lng === 'number' && typeof a.lat === 'number'
}
</script>

<style scoped>
.alert-table { display: flex; flex-direction: column; height: 100%; min-height: 0; }
.at-row {
  display: grid; grid-template-columns: 64px 1.1fr 1.2fr 1.2fr 0.9fr 0.8fr 64px;
  gap: 10px; align-items: center; padding: 8px 4px; font-size: 12px; color: #4A7672;
}
.at-head { color: #5A7A76; font-size: 11px; letter-spacing: .04em; border-bottom: 1px solid rgba(56, 189, 248, .12); }
.at-body { overflow-y: auto; min-height: 0; max-height: 220px; }
.at-body .at-row { border-bottom: 1px solid rgba(56, 189, 248, .06); }
.c-plate { color: #12403E; font-weight: 500; font-variant-numeric: tabular-nums; overflow: hidden; text-overflow: ellipsis; white-space: nowrap; }
.c-loc { overflow: hidden; text-overflow: ellipsis; white-space: nowrap; }
.c-time { font-family: var(--font-num); color: #5A7A76; }
.lv-pill { font-style: normal; font-size: 10.5px; border-radius: 999px; padding: 2px 9px; }
.lv-pill.sev { background: rgba(248, 113, 113, .16); color: #DC4A3C; }
.lv-pill.mid { background: rgba(245, 181, 68, .14); color: #C77F1A; }
.lv-pill.low { background: rgba(90, 113, 148, .18); color: #4A7672; }
.st-dot { display: inline-block; width: 6px; height: 6px; border-radius: 50%; background: #C77F1A; margin-right: 6px; }
.st-dot.done { background: #0E8A8A; }
.loc-btn {
  font: inherit; font-size: 11.5px; color: #0E8A8A; background: rgba(14, 138, 138, .12);
  border: 1px solid rgba(14, 138, 138, .3); border-radius: 7px; padding: 3px 10px; cursor: pointer;
}
.loc-btn:disabled { color: #8AA6A2; background: none; border-color: rgba(56, 189, 248, .12); cursor: not-allowed; }
.at-empty { padding: 18px 4px; font-size: 12px; color: #5A7A76; }
</style>

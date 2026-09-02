<template>
  <!-- 右侧面板：异常通知流 + 离线时长排行 -->
  <PanelCard title="消息通知">
    <div v-if="!notices.length" class="empty">暂无异常通知</div>
    <div v-for="(n, i) in notices" :key="i" class="notice-item">
      <div class="notice-title">{{ n.plate }} 长时间离线</div>
      <div class="notice-desc">已 {{ n.hours }} 小时未上报 · {{ n.city }}</div>
    </div>
  </PanelCard>
  <PanelCard title="离线时长 Top5" style="margin-top:12px">
    <div v-if="!rank.length" class="empty">暂无离线车辆</div>
    <div v-for="(r, i) in rank" :key="i" class="rank-item">
      <div class="rank-head">
        <span class="rank-plate">{{ r.plate }}</span>
        <span class="rank-hours">{{ r.hours }}h</span>
      </div>
      <div class="rank-bar-track">
        <div class="rank-bar" :style="{ width: r.pct + '%' }"></div>
      </div>
    </div>
  </PanelCard>
</template>

<script setup>
const props = defineProps({
  vehicles: { type: Array, default: () => [] }
})

function offlineHours(v) {
  const t = new Date(v.report_time).getTime()
  if (isNaN(t)) return 0
  return Math.max(0, Math.round((Date.now() - t) / 3600000))
}

const offlineList = computed(() =>
  props.vehicles
    .filter(v => Number(v.online) !== 1)
    .map(v => ({ plate: v.plate_no || v.vin, city: v.city_name || '-', hours: offlineHours(v) }))
    .sort((a, b) => b.hours - a.hours)
)

const notices = computed(() => offlineList.value.filter(v => v.hours >= 24).slice(0, 6))

const rank = computed(() => {
  const top = offlineList.value.slice(0, 5)
  const max = top.length ? top[0].hours : 1
  return top.map(v => ({ plate: v.plate, hours: v.hours, pct: Math.max(6, Math.round((v.hours / (max || 1)) * 100)) }))
})
</script>

<style scoped>
.empty { color: var(--text-3); font-size: 12px; text-align: center; padding: 18px 0; }
.notice-item {
  border-left: 3px solid var(--warning);
  background: rgba(245, 158, 11, 0.06);
  border-radius: 6px; padding: 8px 10px; margin-bottom: 8px;
}
.notice-title { font-size: 12px; font-weight: 600; color: var(--text-1); }
.notice-desc { font-size: 11px; color: var(--text-3); margin-top: 2px; }
.rank-item { margin-bottom: 10px; }
.rank-head { display: flex; justify-content: space-between; font-size: 12px; margin-bottom: 4px; }
.rank-plate { color: var(--text-1); font-weight: 600; }
.rank-hours { color: var(--text-3); font-family: var(--font-num); }
.rank-bar-track { height: 6px; border-radius: 3px; background: #F0F0F0; overflow: hidden; }
.rank-bar { height: 100%; border-radius: 3px; background: linear-gradient(90deg, #93C5FD, #2563EB); }
</style>

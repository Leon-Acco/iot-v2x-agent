<template>
  <!-- 设备状态环形图：SVG 周长归一 + 图例 -->
  <div class="donut-wrap">
    <div class="donut-box">
      <svg width="92" height="92" viewBox="0 0 42 42" aria-hidden="true">
        <circle cx="21" cy="21" r="15.9" fill="none" stroke="rgba(56,189,248,.10)" stroke-width="6"/>
        <circle v-for="a in arcs" :key="a.name" cx="21" cy="21" r="15.9" fill="none"
          :stroke="a.color" stroke-width="6" stroke-linecap="round"
          :stroke-dasharray="a.len.toFixed(2) + ' 100'" :stroke-dashoffset="a.offset.toFixed(2)"/>
      </svg>
      <div class="donut-center"><b>{{ total }}</b><span>台</span></div>
    </div>
    <div class="legend">
      <div v-for="s in segments" :key="s.name" class="legend-row">
        <span class="sw" :style="{ background: s.color }"></span>
        <span class="lg-name">{{ s.name }}</span><b>{{ s.value }}</b>
      </div>
    </div>
  </div>
</template>

<script setup>
const props = defineProps({
  segments: { type: Array, default: () => [] }
})
const total = computed(() => props.segments.reduce((s, x) => s + (Number(x.value) || 0), 0))
const arcs = computed(() => {
  const t = total.value || 1
  let prev = 0
  return props.segments.filter(x => Number(x.value) > 0).map(x => {
    const len = (Number(x.value) / t) * 100
    const arc = { ...x, len, offset: 25 - prev }
    prev += len
    return arc
  })
})
</script>

<style scoped>
.donut-wrap { display: flex; align-items: center; gap: 16px; height: 100%; }
.donut-box { position: relative; flex: 0 0 92px; }
.donut-center {
  position: absolute; inset: 0; display: flex; align-items: center; justify-content: center; gap: 2px;
}
.donut-center b { font-size: 17px; color: #E6F1FF; font-family: var(--font-num); font-variant-numeric: tabular-nums; }
.donut-center span { font-size: 10px; color: #5A7194; margin-top: 5px; }
.legend { display: flex; flex-direction: column; gap: 8px; flex: 1; min-width: 0; }
.legend-row { display: flex; align-items: center; gap: 8px; font-size: 12px; color: #8CA3C3; }
.legend-row b { margin-left: auto; color: #E6F1FF; font-variant-numeric: tabular-nums; font-family: var(--font-num); }
.sw { width: 7px; height: 7px; border-radius: 50%; flex: 0 0 7px; }
</style>

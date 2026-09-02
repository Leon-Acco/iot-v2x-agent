<template>
  <!-- KPI 卡：30px Medium 主数字 + 12px 灰色标签，极浅阴影 -->
  <div class="stat-card">
    <div class="stat-value" :style="{ color: accent }">{{ value }}</div>
    <div class="stat-label">{{ label }}</div>
  </div>
</template>

<script setup>
defineProps({
  label: { type: String, required: true },
  value: { type: [String, Number], default: '-' },
  accent: { type: String, default: 'var(--text-1)' }
})
</script>

<style scoped>
.stat-card {
  background: #FFFFFF; border-radius: 12px;
  box-shadow: var(--card-shadow);
  padding: 21px; transition: box-shadow .2s ease;
}
.stat-card:hover { box-shadow: var(--card-shadow-hover); }
.stat-value {
  font-family: var(--font-num); font-size: 30px; font-weight: 500; line-height: 30px;
}
.stat-label { font-size: 12px; color: var(--text-3); margin-top: 6px; }
</style>

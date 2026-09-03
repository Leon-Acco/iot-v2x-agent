<template>
  <!-- 省级分布排行：点击钻取省份 -->
  <PanelCard title="省级分布排行">
    <div v-if="!list.length" class="empty">暂无数据</div>
    <div
      v-for="(p, i) in list"
      :key="i"
      class="pv-item"
      :class="{ active: p.province_name === activeName }"
      @click="$emit('drill', p)"
    >
      <div class="pv-head">
        <span class="pv-name">{{ p.province_name }}</span>
        <span class="pv-num">{{ p.total }} 台</span>
      </div>
      <div class="pv-track">
        <div class="pv-bar" :style="{ width: pct(p.total) + '%' }"></div>
      </div>
      <div class="pv-sub">在线 {{ p.online }} · 离线 {{ p.total - p.online }}</div>
    </div>
  </PanelCard>
</template>

<script setup>
const props = defineProps({
  list: { type: Array, default: () => [] },
  activeName: { type: String, default: '' }
})
defineEmits(['drill'])

const max = computed(() => Math.max(1, ...props.list.map(p => Number(p.total) || 0)))
function pct(total) {
  return Math.max(5, Math.round(((Number(total) || 0) / max.value) * 100))
}
</script>

<style scoped>
.empty { color: var(--text-3); font-size: 12px; text-align: center; padding: 18px 0; }
.pv-item {
  padding: 8px 10px; border-radius: 8px; cursor: pointer; margin-bottom: 4px;
  transition: background .15s ease;
}
.pv-item:hover { background: #F5F5F5; }
.pv-item.active { background: var(--nav-active-bg); }
.pv-head { display: flex; justify-content: space-between; font-size: 12px; margin-bottom: 4px; }
.pv-name { font-weight: 600; color: var(--text-1); }
.pv-num { color: var(--text-3); font-family: var(--font-num); }
.pv-track { height: 6px; border-radius: 3px; background: #F0F0F0; overflow: hidden; }
.pv-bar { height: 100%; border-radius: 3px; background: linear-gradient(90deg, #8fd3bc, #0f8a6a); }
.pv-sub { font-size: 11px; color: var(--text-3); margin-top: 3px; }
</style>

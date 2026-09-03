<template>
  <!-- 排行条形图：品牌分布 / 区域 TOP 通用 -->
  <div class="rank-list">
    <div v-for="it in rows" :key="it.name" class="rank-item" :class="{ clickable }" @click="pick(it)">
      <div class="rank-head">
        <span class="rank-name">{{ it.name }}</span>
        <b>{{ it.value }}</b>
        <span v-if="it.sub !== undefined" class="rank-sub">{{ it.sub }}</span>
      </div>
      <div class="rank-track"><div class="rank-bar" :class="'bar-' + accent" :style="{ width: it.pct + '%' }"></div></div>
    </div>
    <div v-if="!rows.length" class="rank-empty">暂无数据</div>
  </div>
</template>

<script setup>
const props = defineProps({
  items: { type: Array, default: () => [] },
  accent: { type: String, default: 'teal' },
  clickable: { type: Boolean, default: false }
})
const emit = defineEmits(['pick'])
const rows = computed(() => {
  const max = Math.max(...props.items.map(x => Number(x.value) || 0), 1)
  return props.items.map(x => ({
    ...x,
    pct: Math.max(5, Math.round(((Number(x.value) || 0) / max) * 100))
  }))
})
function pick(it) { if (props.clickable) emit('pick', it) }
</script>

<style scoped>
.rank-list { display: flex; flex-direction: column; gap: 9px; }
.rank-item.clickable { cursor: pointer; }
.rank-item.clickable:hover .rank-name { color: #17A05E; }
.rank-head { display: flex; align-items: baseline; gap: 6px; font-size: 12px; color: #5C7266; }
.rank-head b { margin-left: auto; color: #1A2B22; font-variant-numeric: tabular-nums; font-family: var(--font-num); }
.rank-sub { font-size: 10.5px; color: #5C7266; }
.rank-track { height: 5px; border-radius: 3px; background: rgba(138, 162, 148, .08); overflow: hidden; margin-top: 4px; }
.rank-bar { height: 100%; border-radius: 3px; }
.bar-teal { background: linear-gradient(90deg, #8CCFAA, #17A05E); }
.bar-cyan { background: linear-gradient(90deg, #0B7A3C, #1CB56C); }
.rank-empty { font-size: 12px; color: #5C7266; padding: 8px 0; }
</style>

<template>
  <!-- 顶部全局控制栏：区域/品牌/状态筛选 + 刷新 + 实时状态 -->
  <div class="filter-bar">
    <div class="fb-left">
      <select class="fb-select" :value="filters.province" @change="upd('province', $event.target.value)">
        <option value="">全部区域</option>
        <option v-for="p in provinces" :key="p" :value="p">{{ p }}</option>
      </select>
      <select class="fb-select" :value="filters.brand" @change="upd('brand', $event.target.value)">
        <option value="">全部品牌</option>
        <option v-for="b in brands" :key="b" :value="b">{{ b }}</option>
      </select>
      <select class="fb-select" :value="filters.status" @change="upd('status', $event.target.value)">
        <option value="">全部状态</option>
        <option value="0">在线</option>
        <option value="1">停车</option>
        <option value="2">离线</option>
      </select>
      <button class="fb-refresh" @click="emit('refresh')">↻ 刷新</button>
    </div>
    <div class="fb-right">
      <span class="live-dot"></span>实时数据
      <span class="fb-time">最后更新 {{ lastUpdate || '--:--:--' }}</span>
    </div>
  </div>
</template>

<script setup>
const props = defineProps({
  filters: { type: Object, default: () => ({ province: '', brand: '', status: '' }) },
  provinces: { type: Array, default: () => [] },
  brands: { type: Array, default: () => [] },
  lastUpdate: { type: String, default: '' }
})
const emit = defineEmits(['change', 'refresh'])
function upd(key, val) {
  emit('change', { ...props.filters, [key]: val })
}
</script>

<style scoped>
.filter-bar {
  display: flex; align-items: center; justify-content: space-between; gap: 14px;
  background: rgba(255, 255, 255, .74); border: 1px solid rgba(23, 160, 94, .15);
  border-radius: 14px; padding: 10px 16px; backdrop-filter: blur(10px);
}
.fb-left { display: flex; align-items: center; gap: 10px; flex-wrap: wrap; }
.fb-select {
  font: inherit; font-size: 12.5px; color: #5C7266; background: rgba(255, 255, 255, .9);
  border: 1px solid rgba(23, 160, 94, .2); border-radius: 9px; padding: 7px 12px;
  outline: none; cursor: pointer; max-width: 170px;
}
.fb-select:focus { border-color: rgba(23, 160, 94, .55); }
.fb-refresh {
  font: inherit; font-size: 12.5px; color: #17A05E; cursor: pointer;
  background: rgba(23, 160, 94, .12); border: 1px solid rgba(23, 160, 94, .3);
  border-radius: 9px; padding: 7px 14px;
}
.fb-refresh:hover { background: rgba(23, 160, 94, .2); }
.fb-right { display: flex; align-items: center; gap: 8px; font-size: 12px; color: #17A05E; white-space: nowrap; }
.live-dot {
  width: 7px; height: 7px; border-radius: 50%; background: #17A05E;
  box-shadow: 0 0 0 3px rgba(23, 160, 94, .18); animation: pulse 2s ease-in-out infinite;
}
@keyframes pulse { 50% { box-shadow: 0 0 0 6px rgba(23, 160, 94, 0); } }
.fb-time { color: #5C7266; font-family: var(--font-num); }
</style>

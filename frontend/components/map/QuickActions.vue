<template>
  <!-- 快捷操作：均为真实动作（路由跳转 / CSV 导出 / 刷新） -->
  <div class="qa-grid">
    <button class="qa-btn" @click="navigateTo('/anomaly')">
      <span class="qa-ico">⚠</span>告警查询
    </button>
    <button class="qa-btn" @click="emit('export')">
      <span class="qa-ico">↓</span>数据导出
    </button>
    <button class="qa-btn" @click="emit('refresh')">
      <span class="qa-ico">↻</span>刷新数据
    </button>
    <button class="qa-btn" @click="navigateTo('/chat')">
      <span class="qa-ico">→</span>进入工作台
    </button>
  </div>
</template>

<script setup>
const emit = defineEmits(['export', 'refresh'])
</script>

<style scoped>
.qa-grid { display: grid; grid-template-columns: 1fr 1fr; gap: 10px; }
.qa-btn {
  display: flex; align-items: center; gap: 9px; padding: 11px 13px;
  font: inherit; font-size: 12.5px; color: #2A5A57; cursor: pointer;
  background: rgba(255, 255, 255, .74); border: 1px solid rgba(56, 189, 248, .16);
  border-radius: 11px; transition: border-color .2s, color .2s;
}
.qa-btn:hover { border-color: rgba(14, 138, 138, .5); color: #0E8A8A; }
.qa-ico { font-size: 14px; color: #0E8A8A; width: 16px; text-align: center; }
</style>

<template>
  <!-- 内容卡片：白底 12px 圆角，标题栏 + 操作体 + 内容 -->
  <div class="panel">
    <div class="panel-head">
      <span class="panel-title">{{ title }}</span>
      <span class="panel-actions"><slot name="actions" /></span>
    </div>
    <div class="panel-body"><slot /></div>
  </div>
</template>

<script setup>
defineProps({
  title: { type: String, default: '' }
})
</script>

<style scoped>
.panel {
  background: #FFFFFF; border-radius: 12px;
  box-shadow: var(--card-shadow);
  display: flex; flex-direction: column; overflow: hidden;
}
.panel-head {
  display: flex; align-items: center; justify-content: space-between;
  padding: 16px 20px 10px;
}
.panel-title { font-size: 14px; font-weight: 600; color: var(--text-1); }
.panel-body { flex: 1; padding: 0 20px 16px; min-height: 0; overflow: auto; }
</style>

<template>
  <!-- 空态：teal 图标盒 + 标题 + 快捷问题列表 -->
  <div class="frontdesk-empty">
    <div class="frontdesk-icon">
      <svg viewBox="0 0 24 24" width="28" height="28" fill="none" stroke="currentColor" stroke-width="1.8" stroke-linecap="round" stroke-linejoin="round"><path d="M21 11.5a8.38 8.38 0 0 1-8.5 8.5c-1.5 0-2.9-.36-4.1-1L3 20l1.1-5.4a8.5 8.5 0 1 1 16.9-3.1z"/></svg>
    </div>
    <h3>{{ title }}</h3>
    <p>{{ desc }}</p>
    <div v-if="chips.length" class="quick-questions">
      <button v-for="(c, i) in chips" :key="i" class="quick-btn" @click="$emit('pick', c)">{{ c }}</button>
    </div>
  </div>
</template>

<script setup>
defineProps({
  title: { type: String, default: '' },
  desc: { type: String, default: '' },
  chips: { type: Array, default: () => [] }
})
defineEmits(['pick'])
</script>

<style scoped>
.frontdesk-empty {
  display: flex; flex-direction: column; align-items: center; justify-content: center;
  text-align: center; padding: 60px 20px;
}
.frontdesk-icon {
  width: 64px; height: 64px; border-radius: 20px;
  background: var(--primary); color: #fff;
  display: flex; align-items: center; justify-content: center;
  margin-bottom: 24px;
  box-shadow: 0 8px 20px rgba(37, 99, 235, 0.28);
}
.frontdesk-empty h3 { font-size: 18px; font-weight: 500; color: var(--text-1); margin: 0; }
.frontdesk-empty p { font-size: 14px; color: var(--text-2); margin: 6px 0 0; }
.quick-questions {
  display: flex; flex-direction: column; gap: 8px;
  margin-top: 26px; width: 100%; max-width: 400px;
}
.quick-btn {
  width: 100%; padding: 11px 17px;
  background: #fff; border: 1px solid var(--border-default); border-radius: 14px;
  font-size: 13px; color: var(--text-1); cursor: pointer; text-align: left;
  font-family: inherit; transition: border-color .15s, background .15s;
}
.quick-btn:hover { border-color: var(--primary-600); background: var(--primary-light); }
</style>

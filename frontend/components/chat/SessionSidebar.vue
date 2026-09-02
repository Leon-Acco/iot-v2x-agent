<template>
  <!-- 会话侧栏：总前台风格，药丸新建钮 + 软分隔列表 -->
  <aside class="session-bar">
    <button class="new-btn" @click="$emit('create')">+ 新建会话</button>
    <div class="session-list">
      <div v-if="!sessions.length" class="empty">暂无历史会话</div>
      <div
        v-for="s in sessions"
        :key="s.id"
        class="session-item"
        :class="{ active: s.id === activeId }"
        @click="$emit('select', s.id)"
      >
        <span class="session-title">{{ s.title }}</span>
      </div>
    </div>
  </aside>
</template>

<script setup>
defineProps({
  sessions: { type: Array, default: () => [] },
  activeId: { type: String, default: '' }
})
defineEmits(['create', 'select'])
</script>

<style scoped>
.session-bar {
  width: 230px; flex-shrink: 0;
  background: rgba(255, 255, 255, 0.55);
  border-right: 1px solid var(--border-default);
  padding: 16px 12px; display: flex; flex-direction: column;
}
.new-btn {
  width: 100%; height: 40px; justify-content: center; margin-bottom: 14px;
  background: var(--primary); color: #fff; border: none; border-radius: 999px;
  font-size: 13px; font-weight: 500;
  display: inline-flex; align-items: center; gap: 6px;
  transition: background .15s ease;
}
.new-btn:hover { background: var(--primary-deep); }
.session-list { flex: 1; overflow-y: auto; }
.empty { text-align: center; color: var(--text-3); font-size: 12px; padding: 24px 0; }
.session-item {
  min-height: 38px; display: flex; align-items: center;
  padding: 8px 12px; border-radius: 999px; cursor: pointer;
  font-size: 13px; color: var(--text-2); margin-bottom: 2px;
  transition: background .15s ease, color .15s ease;
}
.session-item:hover { background: var(--primary-light); color: var(--primary); }
.session-item.active { background: var(--primary); color: #fff; font-weight: 500; }
.session-title { display: block; overflow: hidden; text-overflow: ellipsis; white-space: nowrap; }
</style>

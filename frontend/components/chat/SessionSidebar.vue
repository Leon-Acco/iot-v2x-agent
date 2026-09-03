<template>
  <!-- 会话侧栏：v3a 面板化——头部「历史会话」+收起钮、虚线新建钮、胶囊会话列表 -->
  <aside class="sessions" aria-label="会话列表">
    <div class="sess-head">
      <span class="st">历史会话</span>
      <button class="fold-btn" type="button" :aria-expanded="folded ? 'false' : 'true'" @click="$emit('fold')">收起</button>
    </div>
    <button class="newchat" @click="$emit('create')">＋ 新建会话</button>
    <div class="session-list">
      <div v-if="!sessions.length" class="empty">暂无历史会话</div>
      <div
        v-for="s in sessions"
        :key="s.id"
        class="sess"
        :class="{ on: s.id === activeId }"
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
  activeId: { type: String, default: '' },
  folded: { type: Boolean, default: false }
})
defineEmits(['create', 'select', 'fold'])
</script>

<style scoped>
.sessions {
  padding: 12px 10px; display: flex; flex-direction: column; gap: 8px;
  align-self: start; max-height: 100%; min-height: 0;
  background: var(--panel); border: 1px solid var(--line); border-radius: 16px;
  box-shadow: var(--shadow);
}
.sess-head { display: flex; align-items: center; justify-content: space-between; gap: 8px; padding: 2px 4px 0; }
.sess-head .st { font-size: 11.5px; font-weight: 700; color: var(--t2); letter-spacing: .06em; }
.fold-btn {
  height: 26px; padding: 0 10px; border-radius: 999px; border: 1px solid var(--line-strong);
  background: #fff; color: var(--t3); font: inherit; font-size: 11px; cursor: pointer;
  white-space: nowrap; flex: 0 0 auto;
}
.fold-btn:hover { color: var(--green-ink); border-color: rgba(23, 160, 94, .5); }
.newchat {
  height: 38px; border: 1px dashed var(--green-deep); border-radius: 999px; background: none;
  color: var(--green-ink); font: inherit; font-size: 13px; font-weight: 600; cursor: pointer;
}
.newchat:hover { background: rgba(23, 160, 94, .07); }
.session-list { flex: 1; overflow-y: auto; min-height: 0; display: flex; flex-direction: column; gap: 2px; }
.empty { text-align: center; color: var(--t3); font-size: 12px; padding: 24px 0; }
.sess {
  padding: 9px 14px; border-radius: 999px; cursor: pointer;
  overflow: hidden; text-overflow: ellipsis; white-space: nowrap;
  font-size: 12.5px; color: var(--t2);
}
.sess:hover { background: var(--field); color: var(--ink); }
.sess.on { background: rgba(23, 160, 94, .12); color: var(--green-ink); font-weight: 600; }
.session-title { display: block; overflow: hidden; text-overflow: ellipsis; white-space: nowrap; }

/* ≤1180：会话栏转横向胶囊条，整条自身横滚（滚动容器化后 min-content 归零，不再撑开页面；对齐 v3a 预览） */
@media (max-width: 1180px) {
  .sessions { grid-column: 1 / -1; flex-direction: row; align-items: center; max-height: none; overflow-x: auto; }
  .sess-head { flex: 0 0 auto; padding: 0; }
  .newchat { flex: 0 0 auto; width: auto; padding: 0 16px; }
  .session-list { flex: 0 0 auto; flex-direction: row; overflow: visible; }
  .sess { flex: 0 0 auto; }
}
</style>

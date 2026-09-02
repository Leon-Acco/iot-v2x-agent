<template>
  <!-- 用户消息气泡：右侧 #F3F3F5，圆角 14/14/4/14，悬浮编辑重发 -->
  <div class="user-msg-row">
    <div class="user-bubble">
      <div class="user-text">{{ content }}</div>
      <button class="edit-btn" :title="editTitle" @click="$emit('edit', content)">编辑重发</button>
    </div>
  </div>
</template>

<script setup>
defineProps({
  content: { type: String, required: true },
  editTitle: { type: String, default: '编辑并重新发送' }
})
defineEmits(['edit'])
</script>

<style scoped>
.user-msg-row { display: flex; justify-content: flex-end; margin: 20px 0 8px; }
.user-bubble { position: relative; max-width: 78%; }
.user-text {
  background: #F3F3F5; color: var(--text-1);
  border-radius: 14px 14px 4px 14px;
  padding: 10px 14px; font-size: 14px; line-height: 1.7;
  white-space: pre-wrap; word-break: break-word;
}
.edit-btn {
  position: absolute; left: -74px; top: 50%; transform: translateY(-50%);
  border: none; background: transparent; color: var(--text-3);
  font-size: 12px; cursor: pointer; padding: 4px 6px; border-radius: 6px;
  opacity: 0; transition: opacity .15s ease;
}
.user-bubble:hover .edit-btn { opacity: 1; }
.edit-btn:hover { background: var(--bg-hover); color: var(--text-1); }
</style>

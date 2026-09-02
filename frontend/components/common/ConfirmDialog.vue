<template>
  <!-- 确认对话框：16px 圆角深阴影 -->
  <Teleport to="body">
    <div v-if="modelValue" class="confirm-mask" @click="$emit('cancel')">
      <div class="confirm-box" @click.stop>
        <div class="confirm-title">{{ title }}</div>
        <div class="confirm-msg">{{ message }}</div>
        <div class="confirm-actions">
          <button class="btn" @click="$emit('cancel')">取消</button>
          <button class="btn btn-primary" :class="{ danger }" @click="$emit('confirm')">确认</button>
        </div>
      </div>
    </div>
  </Teleport>
</template>

<script setup>
defineProps({
  modelValue: { type: Boolean, default: false },
  title: { type: String, default: '确认操作' },
  message: { type: String, default: '' },
  danger: { type: Boolean, default: false }
})
defineEmits(['confirm', 'cancel'])
</script>

<style scoped>
.confirm-mask {
  position: fixed; inset: 0; z-index: 120;
  background: rgba(23, 23, 23, 0.32); display: flex; align-items: center; justify-content: center;
}
.confirm-box {
  width: 380px; padding: 24px; background: #fff;
  border-radius: 16px; box-shadow: var(--shadow-modal);
}
.confirm-title { font-size: 16px; font-weight: 600; margin-bottom: 10px; }
.confirm-msg { font-size: 13px; color: var(--text-2); line-height: 1.7; margin-bottom: 20px; }
.confirm-actions { display: flex; justify-content: flex-end; gap: 8px; }
.btn-primary.danger { background: var(--danger); border-color: var(--danger); }
.btn-primary.danger:hover { background: #B91C1C; border-color: #B91C1C; }
</style>

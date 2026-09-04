<template>
  <!-- 右侧抽屉：遮罩 + 深阴影面板 -->
  <Teleport to="body">
    <div v-if="modelValue" class="drawer-mask" @click="$emit('update:modelValue', false)">
      <div class="drawer" :style="{ width }" @click.stop>
        <div class="drawer-head">
          <span class="drawer-title">{{ title }}</span>
          <button class="drawer-close" @click="$emit('update:modelValue', false)">✕</button>
        </div>
        <div class="drawer-body"><slot /></div>
        <div v-if="$slots.footer" class="drawer-footer"><slot name="footer" /></div>
      </div>
    </div>
  </Teleport>
</template>

<script setup>
defineProps({
  modelValue: { type: Boolean, default: false },
  title: { type: String, default: '' },
  width: { type: String, default: '520px' }
})
defineEmits(['update:modelValue'])
</script>

<style scoped>
.drawer-mask {
  position: fixed; inset: 0; z-index: 100;
  background: rgba(23, 23, 23, 0.24);
}
.drawer {
  position: absolute; right: 0; top: 0; bottom: 0;
  background: #fff; box-shadow: var(--shadow-modal);
  display: flex; flex-direction: column;
  max-width: calc(100vw - 24px); /* 移动端上限：内联 width 超屏时收窄为近全宽 */
  animation: slide-in .18s ease;
}
@keyframes slide-in { from { transform: translateX(40px); opacity: .6; } to { transform: none; opacity: 1; } }
.drawer-head {
  display: flex; align-items: center; justify-content: space-between;
  padding: 18px 20px; border-bottom: 1px solid var(--border-light); flex-shrink: 0;
}
.drawer-title { font-size: 16px; font-weight: 600; }
.drawer-close {
  border: none; background: transparent; font-size: 14px; color: var(--text-3);
  cursor: pointer; padding: 4px 8px; border-radius: 6px;
}
.drawer-close:hover { background: var(--bg-hover); color: var(--text-1); }
.drawer-body { flex: 1; overflow-y: auto; padding: 18px 20px; min-height: 0; }
.drawer-footer {
  padding: 14px 20px; border-top: 1px solid var(--border-light);
  display: flex; justify-content: flex-end; gap: 8px; flex-shrink: 0;
}
</style>

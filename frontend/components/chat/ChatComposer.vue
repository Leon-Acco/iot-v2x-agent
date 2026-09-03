<template>
  <!-- 输入栏：总前台 input-bar，药丸输入框 + 圆形 teal 发送钮 -->
  <div class="input-bar-wrap">
    <div class="input-bar">
      <input
        ref="ta"
        v-model="text"
        class="pill-input"
        :placeholder="placeholder"
        @keydown.enter.exact.prevent="onSend"
      />
      <button v-if="streaming" class="send-btn stop" @click="$emit('stop')" :title="stopTitle">
        <span class="stop-icon"></span>
      </button>
      <button v-else class="send-btn" :disabled="!text.trim()" @click="onSend" :title="sendTitle">
        <svg viewBox="0 0 24 24" width="17" height="17" fill="none" stroke="currentColor" stroke-width="2.4" stroke-linecap="round" stroke-linejoin="round"><path d="M12 19V5M5 12l7-7 7 7"/></svg>
      </button>
    </div>
  </div>
</template>

<script setup>
const props = defineProps({
  streaming: { type: Boolean, default: false },
  placeholder: { type: String, default: '输入你的问题，Enter 发送' },
  sendTitle: { type: String, default: '发送' },
  stopTitle: { type: String, default: '停止生成' }
})
const emit = defineEmits(['send', 'stop'])

const text = ref('')
const ta = ref(null)

function onSend() {
  const q = text.value.trim()
  if (!q || props.streaming) return
  emit('send', q)
  text.value = ''
}

function setText(t) {
  text.value = t
  nextTick(() => { if (ta.value) ta.value.focus() })
}
defineExpose({ setText })
</script>

<style scoped>
.input-bar-wrap {
  flex-shrink: 0; padding: 14px 24px;
  border-top: 1px solid var(--border-light);
  background: rgba(255, 255, 255, 0.55);
}
.input-bar { display: flex; gap: 10px; max-width: 860px; margin: 0 auto; }
.pill-input {
  flex: 1; height: 44px;
  background: #fff; border: 1px solid var(--border-default); border-radius: 999px;
  padding: 0 18px; font-size: 14px; font-family: inherit; color: var(--text-1);
  outline: none; transition: border-color .2s, box-shadow .2s;
}
.pill-input::placeholder { color: var(--text-3); }
.pill-input:focus {
  border-color: var(--green-deep);
  box-shadow: 0 0 0 3px rgba(23, 160, 94, 0.13);
}
.send-btn {
  width: 44px; height: 44px; flex-shrink: 0;
  border: none; border-radius: 50%;
  background: var(--primary-600); color: #fff;
  cursor: pointer; display: flex; align-items: center; justify-content: center;
  transition: background .2s;
}
.send-btn:hover { background: var(--primary); }
.send-btn:disabled { background: var(--border-strong); }
.send-btn.stop { background: var(--bg-dark); }
.stop-icon { width: 12px; height: 12px; background: #fff; border-radius: 2px; }
</style>

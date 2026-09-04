<template>
  <!-- 输入栏：工具选择器 + 总前台 input-bar，药丸输入框 + 圆形 teal 发送钮 -->
  <div class="input-bar-wrap">
    <div class="tool-row" v-if="toolOptions.length">
      <span class="tool-label">工具</span>
      <button
        v-for="t in toolOptions"
        :key="t.id"
        class="tool-chip"
        :class="{ on: forcedTool === t.id }"
        type="button"
        :title="t.description || t.display"
        @click="pick(t.id)"
      >{{ t.display }}</button>
    </div>
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
    <div v-if="forcedTool" class="forced-hint">
      已指定工具：{{ forcedDisplay }}，Agent 只把你的问题转成该工具参数
      <button type="button" class="clear-btn" @click="pick(null)">切回智能路由</button>
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

// 工具选择器：默认智能路由（null），可选 Copilot 工具强制路由；拉取失败静默退化
const toolOptions = ref([])
const forcedTool = ref(null)

onMounted(async () => {
  try {
    const resp = await fetch('/ag-ui/copilot/tools')
    if (!resp.ok) return
    const list = await resp.json()
    if (Array.isArray(list)) toolOptions.value = list
  } catch (e) { /* 静默退化：只有智能路由 */ }
})

const forcedDisplay = computed(() => {
  const t = toolOptions.value.find(x => x.id === forcedTool.value)
  return t ? t.display : forcedTool.value
})

function pick(id) {
  forcedTool.value = forcedTool.value === id ? null : id
}

function onSend() {
  const q = text.value.trim()
  if (!q || props.streaming) return
  emit('send', q, forcedTool.value || undefined)
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
  flex-shrink: 0; padding: 12px 24px 14px;
  border-top: 1px solid var(--border-light);
  background: rgba(255, 255, 255, 0.55);
}
.tool-row { display: flex; align-items: center; gap: 6px; flex-wrap: wrap; margin-bottom: 8px; max-width: 860px; margin-left: auto; margin-right: auto; }
.tool-label { font-size: 10.5px; letter-spacing: 2px; color: var(--text-3); margin-right: 2px; }
.tool-chip {
  height: 24px; padding: 0 11px; border-radius: 999px;
  border: 1px solid var(--border-default); background: #fff;
  color: var(--text-3); font: inherit; font-size: 11.5px; cursor: pointer;
  transition: all .15s ease; white-space: nowrap;
}
.tool-chip:hover { border-color: var(--green-deep); color: var(--green-ink); }
.tool-chip.on {
  background: rgba(23, 160, 94, .12); border-color: var(--green-deep);
  color: var(--green-ink); font-weight: 600;
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
.forced-hint {
  margin: 8px auto 0; max-width: 860px;
  font-size: 11.5px; color: var(--green-ink);
  display: flex; align-items: center; gap: 8px;
}
.clear-btn {
  height: 20px; padding: 0 9px; border-radius: 999px; border: none;
  background: rgba(23, 160, 94, .12); color: var(--green-ink);
  font: inherit; font-size: 11px; cursor: pointer;
}
</style>

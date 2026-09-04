<template>
  <!-- 输入栏：工具选择器（搜索 + 常用置顶）+ 总前台 input-bar，药丸输入框 + 圆形 teal 发送钮 -->
  <div class="input-bar-wrap">
    <div class="tool-row" v-if="toolOptions.length">
      <input
        v-model="toolSearch"
        class="tool-search"
        type="text"
        placeholder="搜工具…"
        aria-label="搜索工具"
      />
      <button
        v-for="t in shownTools"
        :key="t.id"
        class="tool-chip"
        :class="{ on: forcedTool === t.id, hot: hotIds.includes(t.id) }"
        type="button"
        :title="(t.description || t.display) + '（点击使用智能路由时无需选择）'"
        @click="pick(t.id)"
      >{{ t.display }}</button>
      <span v-if="!shownTools.length" class="tool-empty">无匹配工具</span>
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
const toolSearch = ref('')

// 常用置顶：记录每个工具的使用次数（localStorage），Top3 标记为 hot 排前面
const LS_USAGE = 'v2x.tools.usage'
const usage = ref({})
try { usage.value = JSON.parse(localStorage.getItem(LS_USAGE) || '{}') } catch (e) { usage.value = {} }

const hotIds = computed(() => {
  return Object.entries(usage.value)
    .sort((a, b) => b[1] - a[1])
    .slice(0, 3)
    .map(([id]) => id)
})

// 展示顺序：常用在前，搜索词过滤（display/description 匹配）
const shownTools = computed(() => {
  const k = toolSearch.value.trim().toLowerCase()
  let list = toolOptions.value
  if (k) {
    list = list.filter(t =>
      (t.display || '').toLowerCase().includes(k) ||
      (t.description || '').toLowerCase().includes(k) ||
      (t.id || '').toLowerCase().includes(k))
  }
  const hot = hotIds.value
  return list.slice().sort((a, b) => {
    const ha = hot.indexOf(a.id), hb = hot.indexOf(b.id)
    if (ha >= 0 && hb >= 0) return ha - hb
    if (ha >= 0) return -1
    if (hb >= 0) return 1
    return 0
  })
})

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
  if (id) {
    // 使用计数 +1（置顶排序依据）
    usage.value[id] = (usage.value[id] || 0) + 1
    try { localStorage.setItem(LS_USAGE, JSON.stringify(usage.value)) } catch (e) { /* ignore */ }
  }
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
.tool-search {
  height: 24px; width: 86px; padding: 0 10px; border-radius: 999px;
  border: 1px solid var(--border-default); background: #fff;
  font: inherit; font-size: 11.5px; color: var(--text-1); outline: none;
  transition: width .2s ease, border-color .15s;
}
.tool-search:focus { width: 120px; border-color: var(--green-deep); }
.tool-empty { font-size: 11px; color: var(--text-3); }
.tool-chip {
  height: 24px; padding: 0 11px; border-radius: 999px;
  border: 1px solid var(--border-default); background: #fff;
  color: var(--text-3); font: inherit; font-size: 11.5px; cursor: pointer;
  transition: all .15s ease; white-space: nowrap;
}
.tool-chip.hot { border-color: rgba(23, 160, 94, .45); color: var(--green-ink); }
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

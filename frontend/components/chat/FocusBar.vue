<template>
  <!-- 今日需要关注摘要条：数找人——离线/告警聚合，点击直接发起对应 Agent 查询 -->
  <div class="focus-bar" role="status">
    <span class="fb-label">今日需要关注</span>
    <template v-if="loaded">
      <button class="fb-item warn" type="button" @click="$emit('ask', '离线超 24 小时的车有哪些')">
        <span class="fb-num">{{ fmtNum(focus.offline24h) }}</span> 台车离线超 24h
      </button>
      <button class="fb-item danger" type="button" @click="$emit('ask', '近 1 天各类告警次数')">
        <span class="fb-num">{{ fmtNum(focus.alarm24h) }}</span> 次告警（24h）
      </button>
      <span v-if="topStr" class="fb-item muted">高发：{{ topStr }}</span>
    </template>
    <span v-else class="fb-item muted">摘要加载中…</span>
  </div>
</template>

<script setup>
const emit = defineEmits(['ask'])

const focus = ref({})
const loaded = ref(false)

const topStr = computed(() => {
  const top = focus.value.alarmTop
  if (!Array.isArray(top) || !top.length) return ''
  return top.map(x => (x && x.name ? x.name : '') + (x && x.count != null ? ' ' + x.count : '')).filter(Boolean).join(' / ')
})

function fmtNum(v) {
  return v == null ? '—' : Number(v).toLocaleString()
}

async function load() {
  try {
    const resp = await fetch('/ag-ui/copilot/today-focus')
    if (resp.ok) focus.value = await resp.json()
  } catch (e) { /* 摘要条静默退化 */ }
  loaded.value = true
}

/** 对外暴露刷新（工作台手动刷新按钮共用） */
function reload() {
  loaded.value = false
  load()
}
defineExpose({ reload })

onMounted(() => {
  load()
})
</script>

<style scoped>
.focus-bar {
  display: flex; align-items: center; gap: 14px; flex-wrap: wrap;
  min-height: 34px; padding: 6px 16px; margin-bottom: 10px;
  background: var(--panel); border: 1px solid var(--line); border-radius: 12px;
  box-shadow: var(--shadow);
  font-size: 12px; color: var(--text-2, #475569);
}
.fb-label {
  font-size: 10.5px; font-weight: 700; letter-spacing: 2px;
  color: var(--green-ink, #0E6E46); white-space: nowrap;
}
.fb-item {
  border: none; background: none; padding: 2px 8px; border-radius: 999px;
  font: inherit; color: var(--text-2); cursor: default; white-space: nowrap;
}
button.fb-item { cursor: pointer; transition: background .15s; }
button.fb-item:hover { background: rgba(23, 160, 94, .08); }
button.fb-item.warn { color: #B45309; }
button.fb-item.danger { color: #B91C1C; }
.fb-item.muted { color: var(--text-3); }
.fb-num { font-weight: 700; font-variant-numeric: tabular-nums; }

/* 移动端：条目自然折行生长 */
@media (max-width: 760px) {
  .focus-bar { padding: 8px 12px; gap: 4px 8px; }
  .fb-label { letter-spacing: 1px; }
  .fb-item { white-space: normal; }
  /* 英文/数字长 token（时间戳、英文告警名）允许在任意字符处折断，避免溢出 */
  .fb-item { overflow-wrap: anywhere; }
}
</style>

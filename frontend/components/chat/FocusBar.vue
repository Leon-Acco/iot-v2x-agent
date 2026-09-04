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
    <span class="fb-meta">
      <template v-if="focus.dataAsOf">数据截至 {{ focus.dataAsOf }}<template v-if="delayHours != null">（延迟约 {{ delayHours }} 小时）</template> · </template>
      <template v-if="focus.sourceName">{{ focus.sourceName }} · </template>
      <span>{{ clock }}</span>
    </span>
  </div>
</template>

<script setup>
const emit = defineEmits(['ask'])

const focus = ref({})
const loaded = ref(false)
const clock = ref('')

// 时钟：每秒走字（系统时间与数据截至时间分开显示）
let timer = null
function tick() {
  const d = new Date()
  const p = n => String(n).padStart(2, '0')
  clock.value = d.getFullYear() + '/' + (d.getMonth() + 1) + '/' + d.getDate() + ' ' + p(d.getHours()) + ':' + p(d.getMinutes()) + ':' + p(d.getSeconds())
}

// 数据延迟小时数 = 当前时间 - 数据截至时间（解析失败为 null 不显示）
const delayHours = computed(() => {
  const s = focus.value.dataAsOf
  if (!s) return null
  const t = Date.parse(String(s).replace(' ', 'T'))
  if (isNaN(t)) return null
  return Math.max(0, Math.round((Date.now() - t) / 3600000))
})

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
  tick()
  timer = setInterval(tick, 1000)
  load()
})
onBeforeUnmount(() => { if (timer) clearInterval(timer) })
</script>

<style scoped>
.focus-bar {
  display: flex; align-items: center; gap: 14px; flex-wrap: wrap;
  height: 34px; padding: 0 16px; margin-bottom: 10px;
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
.fb-meta { margin-left: auto; font-size: 11px; color: var(--text-3); white-space: nowrap; }
</style>

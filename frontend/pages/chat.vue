<template>
  <!-- P1 设备运营工作叶：会话侧栏 + 对话流 + 输入栏 -->
  <AppShell title="设备运营工作台">
    <div class="chat-page">
      <SessionSidebar
        :sessions="sessions"
        :active-id="activeId"
        @create="newSession"
        @select="selectSession"
      />
      <div class="chat-main">
        <ChatStream
          :messages="messages"
          :follow-ups="followUps"
          :sample-questions="samples"
          @ask="send"
          @edit="onEdit"
          @retry="onRetry"
          @clarify="onClarify"
          @taskcard="onTaskCard"
        />
        <div class="composer-wrap">
          <ChatComposer ref="composer" :streaming="streaming" @send="send" @stop="onStop" />
        </div>
      </div>
      <ResultPanel v-if="activeStream" :stream="activeStream" />
    </div>
  </AppShell>
</template>

<script setup>
const LS_SESSIONS = 'v2x.chat.sessions'
const LS_PREFIX = 'v2x.chat.'

const sessions = ref([])
const activeId = ref('')
const messages = ref([])
const composer = ref(null)
let editFromId = null

const samples = [
  '近 7 天各类告警次数',
  '粤BD96880 最近怎么回事',
  '离线超 24 小时的车有哪些'
]

const followUps = computed(() => {
  const last = messages.value[messages.value.length - 1]
  if (!last || last.role !== 'ai') return []
  if (last.stream.phase.value !== 'done') return []
  // 优先用后端 RUN_FINISHED 下发的追问建议
  const backend = last.stream.followUpSuggestions.value
  if (backend && backend.length) {
    return backend.map(x => typeof x === 'string' ? x : (x.label || x.question || '')).filter(Boolean)
  }
  return [
    '换成折线图看趋势',
    '换个时间段再查',
    '生成处置任务卡'
  ]
})

const streaming = computed(() =>
  messages.value.some(m => m.role === 'ai' && m.stream.isRunning.value)
)

// 三区联动：右侧面板绑定最近一条有查询结果的 AI 消息
const activeStream = computed(() => {
  for (let i = messages.value.length - 1; i >= 0; i--) {
    const m = messages.value[i]
    if (m.role === 'ai' && m.stream.result.value) return m.stream
  }
  return null
})

function uid() {
  return Date.now().toString(36) + Math.random().toString(36).slice(2, 8)
}

function newSession() {
  persist()
  const id = uid()
  sessions.value.unshift({ id, title: '新会话', createdAt: Date.now() })
  selectSession(id)
}

function selectSession(id) {
  if (id === activeId.value) return
  persist()
  activeId.value = id
  messages.value = loadMessages(id)
  persistSessions()
}

function send(q) {
  if (!q || streaming.value) return
  if (editFromId) {
    const idx = messages.value.findIndex(m => m.id === editFromId)
    if (idx >= 0) messages.value = messages.value.slice(0, idx + 1)
    editFromId = null
  }
  const stream = markRaw(useAguiStream())
  messages.value.push(
    { id: uid(), role: 'user', content: q },
    { id: uid(), role: 'ai', question: q, stream }
  )
  const s = sessions.value.find(x => x.id === activeId.value)
  if (s && (s.title === '新会话' || !s.title)) s.title = q.slice(0, 18)
  watch(stream.phase, (p) => { if (p === 'done' || p === 'error' || p === 'cancelled') persist() })
  stream.start(q, 'fleet_copilot', activeId.value)
}

function onStop() {
  const cur = messages.value.find(m => m.role === 'ai' && m.stream.isRunning.value)
  if (cur) cur.stream.cancel()
}

function onEdit(content) {
  const target = [...messages.value].reverse().find(m => m.role === 'user' && m.content === content)
  editFromId = target ? target.id : null
  composer.value && composer.value.setText(content)
}

// 从查询结果跳转 P3：携带能力/参数/结论上下文
function onTaskCard(aiMsg) {
  const res = aiMsg.stream.result.value || {}
  const tools = aiMsg.stream.tools.value || []
  const last = tools.length ? tools[tools.length - 1] : null
  const rawArgs = last && last.args && typeof last.args === 'object' ? last.args : {}
  const params = {}
  Object.keys(rawArgs).filter(k => !k.startsWith('_') && !k.startsWith('acl_')).forEach(k => { params[k] = rawArgs[k] })
  sessionStorage.setItem('v2x.anomaly.context', JSON.stringify({
    title: aiMsg.question,
    capabilityId: res.capabilityId || (last && last.id) || '',
    params,
    conclusion: aiMsg.stream.answer.value || '',
    runId: aiMsg.stream.runId.value || '',
    traceId: aiMsg.stream.traceId.value || ''
  }))
  navigateTo('/anomaly')
}

// 澄清选项：input 类型让用户在输入框补充，其余直接作为追问发送（同会话 threadId 继承上下文）
function onClarify(option) {
  if (!option) return
  if (option.type === 'input') {
    if (composer.value) composer.value.setText('')
    return
  }
  send(option.label)
}

function onRetry(aiMsg) {
  const idx = messages.value.findIndex(m => m.id === aiMsg.id)
  if (idx < 0) return
  const fresh = markRaw(useAguiStream())
  messages.value.splice(idx, 1, { id: aiMsg.id, role: 'ai', question: aiMsg.question, stream: fresh })
  fresh.start(aiMsg.question, 'fleet_copilot', activeId.value)
}

// 持久化：会话列表 + 当前会话消息快照
function persistSessions() {
  localStorage.setItem(LS_SESSIONS, JSON.stringify(sessions.value))
}

function persist() {
  if (!activeId.value) return
  const snapshot = messages.value.map(m => m.role === 'user'
    ? { role: 'user', content: m.content }
    : { role: 'ai', question: m.question, answer: m.stream.answer.value, result: m.stream.result.value })
  localStorage.setItem(LS_PREFIX + activeId.value, JSON.stringify(snapshot))
  persistSessions()
}

function loadMessages(id) {
  try {
    const raw = JSON.parse(localStorage.getItem(LS_PREFIX + id) || '[]')
    if (!Array.isArray(raw)) return []
    return raw.map(m => {
      if (m.role === 'user') return { id: uid(), role: 'user', content: m.content }
      const stream = markRaw(useAguiStream())
      stream.answer.value = m.answer || ''
      if (m.result) stream.result.value = m.result
      stream.phase.value = 'done'
      return { id: uid(), role: 'ai', question: m.question, stream }
    })
  } catch (e) {
    return []
  }
}

onMounted(() => {
  try {
    const raw = JSON.parse(localStorage.getItem(LS_SESSIONS) || '[]')
    if (Array.isArray(raw)) sessions.value = raw
  } catch (e) { /* ignore */ }
  if (!sessions.value.length) {
    sessions.value = [{ id: uid(), title: '新会话', createdAt: Date.now() }]
  }
  activeId.value = sessions.value[0].id
  messages.value = loadMessages(activeId.value)
  persistSessions()
})
</script>

<style scoped>
.chat-page {
  display: flex; height: calc(100vh - 56px - 48px);
  overflow: hidden;
}
.chat-main { flex: 1; display: flex; flex-direction: column; min-width: 0; }
.composer-wrap { flex-shrink: 0; }
.composer-wrap :deep(.input-bar-wrap) {
  background: transparent; border-top-color: var(--border-default);
}
</style>

<template>
  <!-- P1 设备运营工作台：会话｜数据画布｜聊天室（最右最宽），会话栏可折叠 -->
  <AppShell title="设备运营工作台">
    <template #topbar>
      <button class="sess-restore" type="button" :aria-expanded="sessFolded ? 'true' : 'false'" @click="toggleSess">⟨ 历史会话</button>
    </template>
    <div class="workbench">
      <SessionSidebar
        :sessions="sessions"
        :active-id="activeId"
        :folded="sessFolded"
        @create="newSession"
        @select="selectSession"
        @fold="toggleSess"
      />
      <WorkCanvas :stream="activeStream" />
      <div class="chat-main">
        <div class="chat-head">
          <span class="ck">对话流</span>
          <span class="ca">设备运营 Agent</span>
          <span class="cb-live" aria-hidden="true"></span>
          <button class="btn pdf-btn" type="button" :disabled="exporting" @click="exportPdf">{{ exporting ? '生成中…' : '导出 PDF' }}</button>
        </div>
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
const sessFolded = ref(false)
const exporting = ref(false)
let editFromId = null

// 历史会话折叠：body 类驱动（全局 folded-sess 规则收起侧栏并收窄网格）
function toggleSess() {
  sessFolded.value = !sessFolded.value
  document.body.classList.toggle('folded-sess', sessFolded.value)
}

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

// 导出对话 PDF：html2canvas 长截屏 .chat-column（聊天列完整内容）+ jsPDF 单页导出（参考 portal-adas CreatePdf.vue）
async function exportPdf() {
  const target = document.querySelector('.chat-column')
  if (!target || exporting.value) return
  exporting.value = true
  try {
    const [{ default: html2canvas }, { jsPDF }] = await Promise.all([import('html2canvas'), import('jspdf')])
    const widthValue = target.offsetWidth
    const heightValue = target.scrollHeight
    const canvas = await html2canvas(target, {
      allowTaint: true, scrollY: 0, scrollX: 0,
      scale: 2, useCORS: true, backgroundColor: '#FFFFFF',
      width: widthValue, height: heightValue
    })
    const imageUrl = canvas.toDataURL('image/jpeg', 0.6)
    const pdf = new jsPDF('', 'px', [widthValue, heightValue])
    pdf.addImage(imageUrl, 'jpeg', 0, 0, widthValue, heightValue)
    const s = sessions.value.find(x => x.id === activeId.value)
    pdf.save(((s && s.title) || '对话') + '-对话.pdf')
  } catch (e) {
    alert('PDF 生成失败：' + (e && e.message ? e.message : e))
  } finally {
    exporting.value = false
  }
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
/* v3a 运营工作台三栏：会话 200px｜数据画布（≤400px）｜聊天室最右最宽；高度吃满视口（吃掉 .page 底衬） */
.workbench {
  display: grid; grid-template-columns: 200px minmax(0, 400px) minmax(0, 1fr);
  gap: 12px; align-items: stretch;
  height: calc(100vh - var(--topbar-h) - var(--topbar-gap));
  margin-top: 0px;
  margin-bottom: calc(-1 * var(--page-pad-b));
}
.workbench > * { min-height: 0; min-width: 0; }
.sess-restore {
  display: none; height: 26px; padding: 0 12px; border-radius: 999px;
  border: 1px dashed rgba(23, 160, 94, .55); background: rgba(23, 160, 94, .06);
  color: var(--green-ink); font: inherit; font-size: 11.5px; cursor: pointer; align-items: center;
}
.sess-restore:hover { background: rgba(23, 160, 94, .12); }
.chat-main {
  display: flex; flex-direction: column; min-width: 0;
  padding: 0 14px 14px; overflow: hidden;
  background: var(--panel); border: 1px solid var(--line); border-radius: 16px;
  box-shadow: var(--shadow), 0 0 0 1px rgba(23, 160, 94, .18);
}
.chat-head {
  display: flex; align-items: center; gap: 10px; flex-wrap: wrap;
  margin: 0 -14px 14px; padding: 12px 16px;
  background: rgba(23, 160, 94, .07);
  border-bottom: 1px solid var(--line); border-radius: 16px 16px 0 0;
}
.chat-head .ck { font-family: var(--f-mono); font-size: 10px; letter-spacing: 3px; color: var(--green-ink); }
.chat-head .ca { font-size: 13.5px; font-weight: 700; }
.chat-head .cb-live { width: 8px; height: 8px; border-radius: 50%; background: var(--green); margin-left: auto; }
.pdf-btn { height: 26px; padding: 0 12px; font-size: 11.5px; }
.pdf-btn:disabled { opacity: .6; cursor: wait; }
.composer-wrap { flex-shrink: 0; }
.composer-wrap :deep(.input-bar-wrap) {
  background: transparent; border-top-color: var(--border-default);
}

@media (max-width: 1180px) {
  .workbench { grid-template-columns: minmax(0, 360px) minmax(0, 1fr); }
}
@media (max-width: 880px) {
  .workbench { grid-template-columns: minmax(0, 1fr); height: auto; margin-bottom: 0; }
  .workbench > :deep(.work-canvas) { order: 3; }
  .chat-main { order: 2; }
}
</style>

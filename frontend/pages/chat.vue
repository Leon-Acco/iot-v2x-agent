<template>
  <!-- P1 设备运营工作台：会话｜数据画布｜聊天室（最右最宽），会话栏可折叠 -->
  <AppShell title="设备运营工作台">
    <div class="wb-wrap">
      <FocusBar ref="focusBar" @ask="q => send(q)" />
      <div class="workbench" :style="gridStyle">
        <SessionSidebar
          :sessions="sessions"
          :active-id="activeId"
          :folded="sessFolded"
          @create="newSession"
          @select="selectSession"
          @fold="toggleSess"
          @rename="onRename"
          @delete="onDeleteSession"
        />
        <WorkCanvas :rounds="rounds" @remove-item="removeItem" @drill="onDrillAsk" @refresh="onRefreshLast" />
        <div class="col-resizer" role="separator" aria-orientation="vertical" @pointerdown="startResize"></div>
        <div class="chat-main">
        <div class="chat-head">
          <span class="ck">对话流</span>
          <span class="ca">设备运营 Agent</span>
          <span class="cb-live" aria-hidden="true"></span>
        </div>
        <ChatStream
          :messages="messages"
          :follow-ups="followUps"
          :sample-questions="samples"
          @ask="q => send(q)"
          @edit="onEdit"
          @retry="onRetry"
          @clarify="onClarify"
        />
        <div class="composer-wrap">
          <ChatComposer ref="composer" :streaming="streaming" @send="send" @stop="onStop" />
        </div>
      </div>
      </div>
    </div>
  </AppShell>
</template>

<script setup>
const LS_SESSIONS = 'v2x.chat.sessions'
const LS_PREFIX = 'v2x.chat.'
const LS_SESS_FOLDED = 'v2x.sess.folded'
const SS_ACTIVERUN = 'v2x.chat.activerun'

const sessions = ref([])
const activeId = ref('')
const messages = ref([])
const composer = ref(null)
const sessFolded = ref(false)
let editFromId = null

// 历史会话折叠：--sess-col 收窄为 60px 窄条（收起/展开按钮都在侧栏内），localStorage 持久化
function toggleSess() {
  sessFolded.value = !sessFolded.value
  try { localStorage.setItem(LS_SESS_FOLDED, sessFolded.value ? '1' : '0') } catch (e) { /* ignore */ }
}

const samples = [
  '近 7 天各类告警次数',
  '粤C10003 最近怎么回事',
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

// ---------- 画布/对话列拖拽调宽：数据工作台为主、对话为辅 ----------
const LS_COLW = 'v2x.chat.colw'
const chatW = ref(400)
try { const w = parseInt(localStorage.getItem(LS_COLW) || '', 10); if (w >= 320 && w <= 620) chatW.value = w } catch (e) { /* ignore */ }
const focusBar = ref(null)

// 对话列宽度固定（用户反馈"框框不固定"：查询中自动收窄会让对话区跳动，已移除）
// 列宽走 CSS 变量：响应式断点仍可在 CSS 里覆盖（inline grid-template 会让 media 失效）
const gridStyle = computed(() => {
  return {
    '--chat-col': chatW.value + 'px',
    '--sess-col': sessFolded.value ? '60px' : '200px'
  }
})

function startResize(e) {
  e.preventDefault()
  const onMove = ev => {
    const w = Math.min(620, Math.max(320, window.innerWidth - ev.clientX - 24))
    chatW.value = w
  }
  const onUp = () => {
    window.removeEventListener('pointermove', onMove)
    window.removeEventListener('pointerup', onUp)
    document.body.classList.remove('resizing-col')
    try { localStorage.setItem(LS_COLW, String(chatW.value)) } catch (err) { /* ignore */ }
  }
  document.body.classList.add('resizing-col')
  window.addEventListener('pointermove', onMove)
  window.addEventListener('pointerup', onUp)
}

// ---------- 画布联动回调 ----------
/** 图表点柱下钻：把维度值回填为可执行的追问（下钻路径回显到对话里） */
function onDrillAsk(q) {
  if (q) send(q)
}

/** 手动刷新：重跑最近一次 AI 查询（拿最新数据）并刷新今日关注 */
function onRefreshLast() {
  const last = [...messages.value].reverse().find(m => m.role === 'ai' && m.question)
  if (last) onRetry(last)
  if (focusBar.value) focusBar.value.reload()
}

// ---------- 会话级数据工作台流水账：按提问轮次分组，会话内产生什么就展示什么 ----------
const rounds = ref([])
/** 当前进行中的轮次（send/onRetry/replay 时开启，产物钩子向它追加条目） */
let currentRound = null

/** 轮级能力徽章去重收集 */
function addCapability(round, name) {
  if (name && !round.capabilities.includes(name)) round.capabilities.push(name)
}

/** 开启新一轮提问（重试/重放同样开新轮，保留历史轮——流水账语义） */
function startRound(question) {
  currentRound = { id: 'r-' + uid(), ts: Date.now(), question: question || '', capabilities: [], items: [] }
  rounds.value.push(currentRound)
}

/**
 * 产物钩子：useAguiStream 把会话内产物（result/chart/viz）实时回调到这里，
 * 按到达顺序追加进当前轮（不去重、不覆盖）。
 */
function collectArtifact(a) {
  if (!a || !a.kind || !currentRound) return
  const r = currentRound
  if (a.kind === 'result' && a.payload && a.payload.columns) {
    r.items.push({
      kind: 'result',
      data: a.payload,
      chart: null,
      label: a.payload.capabilityDisplay || (a.tool && a.tool.name) || a.payload.capabilityId || '查询结果',
      toolName: (a.tool && a.tool.toolName) || null
    })
    addCapability(r, a.payload.capabilityDisplay || (a.tool && a.tool.name))
  } else if (a.kind === 'chart' && a.payload) {
    // 图表挂到本轮最后一个未挂图表的结果条目（后端 result->chart 成对发射）
    const target = [...r.items].reverse().find(it => it.kind === 'result' && !it.chart)
    if (target) {
      target.chart = a.payload
    } else {
      r.items.push({ kind: 'result', data: null, chart: a.payload,
        label: a.payload.capabilityDisplay || '图表', toolName: null })
    }
    if (a.payload.capabilityDisplay) addCapability(r, a.payload.capabilityDisplay)
  } else if (a.kind === 'viz' && a.payload) {
    r.items.push({
      kind: 'viz',
      data: a.payload,
      label: a.payload.title || '可视化',
      toolName: a.payload.tool || (a.tool && a.tool.toolName) || null
    })
    if (a.payload.tool === 'generate_visualization') addCapability(r, '可视化')
  }
  rounds.value = [...rounds.value]
}

/** 切会话/刷新/恢复后：从全部 AI 消息的帧快照重建流水账 */
function rebuildRounds() {
  const out = []
  for (const m of messages.value) {
    if (m.role !== 'ai' || !m.stream) continue
    const st = m.stream
    const r = { id: 'r-' + uid(), ts: m.ts || Date.now(), question: m.question || '', capabilities: [], items: [] }
    // 各工具的查询结果（快照 tools[].result；主链路单工具、copilot 多工具）
    const toolResults = (st.tools.value || []).filter(t => t.result && t.result.columns)
    for (const t of toolResults) {
      r.items.push({
        kind: 'result',
        data: t.result,
        chart: null,
        label: t.result.capabilityDisplay || t.name || '查询结果',
        toolName: t.toolName || null
      })
      addCapability(r, t.result.capabilityDisplay || t.name)
    }
    // 轮级图表快照挂到最后一个结果条目
    if (st.result && st.result.value && st.result.value.chart) {
      const last = r.items[r.items.length - 1]
      if (last && last.kind === 'result') last.chart = st.result.value.chart
    }
    for (const v of (st.visualizations.value || [])) {
      r.items.push({ kind: 'viz', data: v, label: v.title || '可视化', toolName: v.tool || null })
      if (v.tool === 'generate_visualization') addCapability(r, '可视化')
    }
    if (r.items.length) out.push(r)
  }
  rounds.value = out
}

/** 移除轮内单个条目 */
function removeItem(roundId, itemIdx) {
  const r = rounds.value.find(x => x.id === roundId)
  if (r) {
    r.items.splice(itemIdx, 1)
    if (!r.items.length) rounds.value = rounds.value.filter(x => x.id !== roundId)
    else rounds.value = [...rounds.value]
  }
}

function uid() {
  return Date.now().toString(36) + Math.random().toString(36).slice(2, 8)
}

async function apiGet(url) {
  const resp = await fetch(url)
  if (!resp.ok) throw new Error('HTTP ' + resp.status)
  return resp.json()
}

// ---------- 会话数据源（后端优先，localStorage 降级） ----------

/** 拉取后端会话列表（agent_session 落库，服务重启不丢）；空则降级本地/新建 */
async function loadSessions() {
  try {
    const list = await apiGet('/ag-ui/sessions?profileId=fleet_copilot')
    if (Array.isArray(list) && list.length) {
      sessions.value = list.map(s => ({
        id: s.threadId,
        sessionId: s.sessionId,
        threadId: s.threadId,
        title: s.title || '会话',
        turnCount: s.turnCount,
        createdAt: Date.parse(s.createdAt || '') || Date.now()
      }))
      return
    }
  } catch (e) { /* 后端不可用走降级 */ }
  try {
    const raw = JSON.parse(localStorage.getItem(LS_SESSIONS) || '[]')
    if (Array.isArray(raw) && raw.length) {
      sessions.value = raw.map(s => ({ id: s.id, sessionId: null, threadId: s.id, title: s.title || '会话', createdAt: s.createdAt }))
      return
    }
  } catch (e) { /* ignore */ }
  sessions.value = [{ id: uid(), sessionId: null, threadId: '', title: '新会话', createdAt: Date.now() }]
  sessions.value[0].threadId = sessions.value[0].id
}

/** payload 帧快照 -> useAguiStream 完整恢复（answer/tools/result/chart/visualizations） */
function hydrateStream(m) {
  const stream = markRaw(useAguiStream())
  const p = m.payload || {}
  stream.answer.value = p.answer || String(m.content || '')
  if (Array.isArray(p.tools)) {
    stream.tools.value = p.tools.map(t => ({
      id: t.id, name: t.name, status: t.status || 'done', args: t.args || null,
      result: t.result || null,
      summary: t.result && t.result.rowCount != null ? '→ 找到 ' + t.result.rowCount + ' 条' : ''
    }))
  }
  if (p.result && p.result.columns) stream.result.value = p.result
  if (p.chart && stream.result.value) {
    stream.result.value = Object.assign({}, stream.result.value, { chart: p.chart })
  }
  if (Array.isArray(p.visualizations)) stream.visualizations.value = p.visualizations
  if (p.traceId) stream.traceId.value = p.traceId
  if (Array.isArray(p.followUps) && p.followUps.length) stream.followUpSuggestions.value = p.followUps
  stream.phase.value = 'done'
  return stream
}

/** 从后端拉会话消息并重建（VIS_SPEC 帧快照直接重放进 visualizations）；失败降级 localStorage */
async function loadMessagesRemote(threadId) {
  const s = sessions.value.find(x => x.threadId === threadId)
  if (s && s.sessionId) {
    try {
      const list = await apiGet('/ag-ui/sessions/' + s.sessionId + '/messages')
      if (Array.isArray(list)) {
        const out = []
        for (const m of list) {
          if (m.role === 'user') {
            out.push({ id: uid(), role: 'user', content: String(m.content || '') })
          } else if (m.role === 'assistant') {
            out.push({ id: uid(), role: 'ai', question: '', stream: hydrateStream(m) })
          }
        }
        // ai 消息的 question 取前一条 user 内容
        let lastQ = ''
        for (const m of out) {
          if (m.role === 'user') lastQ = m.content
          else if (m.role === 'ai') m.question = lastQ
        }
        return out
      }
    } catch (e) { /* 降级 localStorage */ }
  }
  return loadMessages(threadId)
}

function newSession() {
  persist()
  const id = uid()
  sessions.value.unshift({ id, sessionId: null, threadId: id, title: '新会话', createdAt: Date.now() })
  activeId.value = id
  messages.value = []
  rounds.value = []
  currentRound = null
  persistSessions()
}

async function selectSession(threadId) {
  if (threadId === activeId.value) return
  persist()
  activeId.value = threadId
  messages.value = await loadMessagesRemote(threadId)
  restoreActiveStream(threadId)
  rebuildRounds()
  persistSessions()
}

// ---------- 发送 / 多轮上下文 / 强制路由 ----------

/** 发送一条消息：history 携带本会话多轮上下文（user+assistant），forcedTool 为用户指定工具 */
function send(q, forcedTool) {
  if (!q || streaming.value) return
  if (editFromId) {
    const idx = messages.value.findIndex(m => m.id === editFromId)
    if (idx >= 0) messages.value = messages.value.slice(0, idx + 1)
    editFromId = null
  }
  const history = messages.value
    .map(m => m.role === 'user'
      ? { role: 'user', content: m.content }
      : { role: 'assistant', content: m.stream.answer.value || '' })
    .filter(m => m.content)
  const stream = markRaw(useAguiStream())
  startRound(q)
  stream.setArtifactHook(collectArtifact)
  const aiMsg = { id: uid(), role: 'ai', question: q, stream, ts: Date.now() }
  messages.value.push({ id: uid(), role: 'user', content: q }, aiMsg)
  const s = sessions.value.find(x => x.threadId === activeId.value)
  if (s && (s.title === '新会话' || !s.title)) s.title = q.slice(0, 18)
  watch(stream.phase, (p) => {
    if (p === 'done' || p === 'error' || p === 'cancelled') {
      persist()
      useChatWorkspace().untrack(aiMsg.id)
      clearActiveRun()
    }
  })
  const runId = stream.start(q, 'fleet_copilot', activeId.value, { history, forcedTool })
  useChatWorkspace().track(aiMsg.id, stream, activeId.value, runId, q)
  try {
    sessionStorage.setItem(SS_ACTIVERUN, JSON.stringify({ threadId: activeId.value, runId, question: q, at: Date.now() }))
  } catch (e) { /* ignore */ }
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
  const history = messages.value.slice(0, idx)
    .map(m => m.role === 'user'
      ? { role: 'user', content: m.content }
      : { role: 'assistant', content: m.stream.answer.value || '' })
    .filter(m => m.content)
  const fresh = markRaw(useAguiStream())
  startRound(aiMsg.question)
  fresh.setArtifactHook(collectArtifact)
  messages.value.splice(idx, 1, { id: aiMsg.id, role: 'ai', question: aiMsg.question, stream: fresh, ts: Date.now() })
  const runId = fresh.start(aiMsg.question, 'fleet_copilot', activeId.value, { history })
  const msgId = aiMsg.id
  useChatWorkspace().track(msgId, fresh, activeId.value, runId, aiMsg.question)
  watch(fresh.phase, (p) => {
    if (p === 'done' || p === 'error' || p === 'cancelled') {
      persist()
      useChatWorkspace().untrack(msgId)
      clearActiveRun()
    }
  })
}

// ---------- 流保持：切页恢复（单例活流）+ 刷新恢复（replay 补帧） ----------

function clearActiveRun() {
  try { sessionStorage.removeItem(SS_ACTIVERUN) } catch (e) { /* ignore */ }
}

function bindFinishWatch(msgId, stream) {
  watch(stream.phase, (p) => {
    if (p === 'done' || p === 'error' || p === 'cancelled') {
      persist()
      useChatWorkspace().untrack(msgId)
      clearActiveRun()
    }
  })
}

/** 页面重进恢复：场景1 SPA 切页（单例流还活着）→ 直接挂回；场景2 刷新 → replay 补帧 */
function restoreActiveStream(threadId) {
  const ws = useChatWorkspace()
  if (ws.streams.size && ws.activeThread === threadId) {
    const raw = useNuxtApp().$chatWorkspace
    let liveMsgId = null
    let liveStream = null
    raw.streams.forEach((st, id) => { liveMsgId = id; liveStream = st })
    if (liveStream) {
      // 弹掉后端重建的空 assistant 壳（run 未结束未落库的那轮）
      const last = messages.value[messages.value.length - 1]
      if (last && last.role === 'ai' && !last.stream.answer.value && !last.stream.tools.value.length) {
        messages.value.pop()
      }
      messages.value.push({ id: liveMsgId, role: 'ai', question: raw.activeQuestion || '', stream: liveStream })
      bindFinishWatch(liveMsgId, liveStream)
    }
    return
  }
  replayRestore(threadId)
}

/** 浏览器刷新恢复：sessionStorage 记录的 runId 从 Redis 事件缓冲全量重放（TTL 10 分钟） */
function replayRestore(threadId) {
  let saved = null
  try { saved = JSON.parse(sessionStorage.getItem(SS_ACTIVERUN) || 'null') } catch (e) { /* ignore */ }
  if (!saved || saved.threadId !== threadId || !saved.runId) return
  if (Date.now() - (saved.at || 0) > 10 * 60 * 1000) { clearActiveRun(); return }
  // 末轮 assistant 已落库（run 已结束）则无需补帧
  const last = messages.value[messages.value.length - 1]
  if (last && last.role === 'ai' && last.stream.answer.value) { clearActiveRun(); return }
  if (last && last.role === 'ai' && !last.stream.answer.value) messages.value.pop()
  const stream = markRaw(useAguiStream())
  startRound(saved.question || '')
  stream.setArtifactHook(collectArtifact)
  stream.phase.value = 'understanding'
  stream.runId.value = saved.runId
  const msg = { id: uid(), role: 'ai', question: saved.question || '', stream, ts: Date.now() }
  messages.value.push(msg)
  bindFinishWatch(msg.id, stream)
  const handle = replayRun(saved.runId, stream.onEvent, () => {
    // 重放流结束（complete 或超时）：已有内容则收尾为 done，否则取消态
    if (stream.isRunning.value) {
      if (stream.answer.value || stream.tools.value.length) stream.phase.value = 'done'
      else stream.phase.value = 'cancelled'
    }
    handle.close()
  })
}

// ---------- 会话重命名 / 删除 ----------

async function onRename(threadId, title) {
  const s = sessions.value.find(x => x.threadId === threadId)
  if (!s || !s.sessionId || !title) return
  try {
    await fetch('/ag-ui/sessions/' + s.sessionId + '/rename', {
      method: 'POST',
      headers: { 'Content-Type': 'application/json' },
      body: JSON.stringify({ title })
    })
    s.title = title
  } catch (e) { /* ignore */ }
}

async function onDeleteSession(threadId) {
  const s = sessions.value.find(x => x.threadId === threadId)
  if (!s) return
  if (!window.confirm('删除该会话及全部记录？')) return
  try {
    if (s.sessionId) await fetch('/ag-ui/sessions/' + s.sessionId, { method: 'DELETE' })
  } catch (e) { /* ignore */ }
  sessions.value = sessions.value.filter(x => x.threadId !== threadId)
  try { localStorage.removeItem(LS_PREFIX + threadId) } catch (e) { /* ignore */ }
  if (activeId.value === threadId) {
    if (sessions.value.length) selectSession(sessions.value[0].threadId)
    else newSession()
  }
  persistSessions()
}

// ---------- localStorage 快照（降级链兜底） ----------

function persistSessions() {
  try {
    localStorage.setItem(LS_SESSIONS, JSON.stringify(sessions.value.map(s => ({
      id: s.threadId, title: s.title, createdAt: s.createdAt
    }))))
  } catch (e) { /* ignore */ }
}

function persist() {
  if (!activeId.value) return
  const snapshot = messages.value.map(m => m.role === 'user'
    ? { role: 'user', content: m.content }
    : { role: 'ai', question: m.question, answer: m.stream.answer.value, result: m.stream.result.value })
  try { localStorage.setItem(LS_PREFIX + activeId.value, JSON.stringify(snapshot)) } catch (e) { /* ignore */ }
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

onMounted(async () => {
  // 折叠态恢复：默认收起为窄条（显式存过 '0' 才展开）
  sessFolded.value = localStorage.getItem(LS_SESS_FOLDED) !== '0'
  await loadSessions()
  // 活跃流所在会话优先（SPA 切页回来接续渲染），否则最近会话
  const ws = useChatWorkspace()
  const initThread = (ws.streams.size && ws.activeThread) || (sessions.value[0] && sessions.value[0].threadId)
  if (!initThread) { newSession(); return }
  activeId.value = initThread
  messages.value = await loadMessagesRemote(initThread)
  restoreActiveStream(initThread)
  rebuildRounds()
  persistSessions()
})
</script>

<style scoped>
/* 运营工作台：会话栏｜数据画布（主位 1fr）｜拖拽条｜对话流（窄列） */
.wb-wrap {
  display: flex; flex-direction: column;
  height: calc(100vh - var(--topbar-h) - var(--topbar-gap));
  margin-bottom: calc(-1 * var(--page-pad-b));
}
.workbench {
  flex: 1; min-height: 0;
  display: grid;
  grid-template-columns: var(--sess-col, 200px) minmax(0, 1fr) 6px var(--chat-col, 400px);
  gap: 12px; align-items: stretch;
}
.workbench > * { min-height: 0; min-width: 0; }
/* 画布/对话列拖拽条：hover 高亮，拖动中全局禁选中 */
.col-resizer { cursor: col-resize; border-radius: 3px; background: transparent; transition: background .15s; }
.col-resizer:hover { background: rgba(23, 160, 94, .25); }
:global(body.resizing-col) { cursor: col-resize; user-select: none; }
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
.chat-head .cb-live { width: 8px; height: 8px; border-radius: 50%; background: var(--green); }
.composer-wrap { flex-shrink: 0; }
.composer-wrap :deep(.input-bar-wrap) {
  background: transparent; border-top-color: var(--border-default);
}

@media (max-width: 1180px) {
  .workbench { grid-template-columns: var(--sess-col, 170px) minmax(0, 1fr) 6px min(var(--chat-col, 400px), 360px); }
}
@media (max-width: 880px) {
  .workbench { grid-template-columns: minmax(0, 1fr); height: auto; margin-bottom: 0; }
  .wb-wrap { height: auto; margin-bottom: 0; }
  .col-resizer { display: none; }
  .workbench > :deep(.work-canvas) { order: 3; }
  .chat-main { order: 2; }
}
</style>

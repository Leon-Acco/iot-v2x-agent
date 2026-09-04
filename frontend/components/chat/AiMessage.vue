<template>
  <!-- AI 消息：无气泡融入背景，头像+名称头部、思考折叠块、工具叙事、「结论」标签、pill 徽章、消息折叠 -->
  <div class="ai-msg">
    <div class="ai-head">
      <span class="ai-avatar">A</span>
      <span class="ai-name">设备运营 Agent</span>
      <span v-if="phaseText" class="ai-phase">{{ phaseText }}<ThinkingDots /></span>
      <button v-if="canCollapse" class="collapse-btn" type="button" @click="collapsed = !collapsed">
        {{ collapsed ? '展开' : '收起' }}
      </button>
    </div>

    <!-- 思考过程：流式中实时展开，完成后折叠为一行可再展开 -->
    <div v-if="thinkingText" class="think-block" :class="{ open: thinkOpen }">
      <button class="think-toggle" type="button" @click="thinkOpen = !thinkOpen">
        <svg viewBox="0 0 24 24" width="13" height="13" fill="none" stroke="currentColor" stroke-width="2"><path d="M12 3a6 6 0 0 1 6 6c0 2.5-1.5 4-3 5.5-.8.8-1 1.5-1 2.5h-4c0-1-.2-1.7-1-2.5C7.5 13 6 11.5 6 9a6 6 0 0 1 6-6z"/><path d="M10 20h4"/></svg>
        {{ thinkingActive ? '正在思考…' : '思考过程（' + thinkingText.length + ' 字）' }}
        <span class="think-caret" :class="{ up: thinkOpen }">▾</span>
      </button>
      <div v-show="thinkOpen" ref="thinkBodyRef" class="think-body">
        <template v-for="(seg, i) in thinkSegments" :key="i">
          <div v-if="seg.kind === 'title'" class="seg-title">
            <span class="seg-icon">{{ seg.icon }}</span>{{ seg.text }}
          </div>
          <div v-else-if="seg.kind === 'gap'" class="seg-gap"></div>
          <div v-else class="seg-line" :class="{ receipt: seg.receipt }">{{ seg.text }}</div>
        </template>
      </div>
    </div>

    <div v-if="collapsed" class="collapsed-summary" @click="collapsed = false">
      {{ answer.slice(0, 50) }}{{ answer.length > 50 ? '…' : '' }}
      <span class="cs-meta">{{ toolCallCount }} 次调用</span>
    </div>

    <template v-else>
      <ToolCallNarrative :tools="tools" @retry="$emit('retry')" />

      <template v-if="answer">
        <div class="answer-label">结论</div>
        <!-- Markdown 渲染：**加粗**/列表/标题（流式期间实时重渲，delta 已 90ms 批量） -->
        <div class="ai-answer md" v-html="answerHtml"></div>
        <div v-if="cancelled" class="cancelled-mark">（已手动停止生成）</div>
      </template>
      <div v-else-if="cancelled" class="ai-answer cancelled-mark">（已手动停止生成）</div>

      <!-- 关键数字卡片：小结果集（≤4 行且有数值列）升级为卡片行，差异一目了然 -->
      <div v-if="metricCards.length" class="metric-cards">
        <div v-for="(c, i) in metricCards" :key="i" class="metric-card">
          <div class="mc-value">{{ c.value }}<span v-if="c.unit" class="mc-unit">{{ c.unit }}</span></div>
          <div class="mc-label">{{ c.label }}</div>
        </div>
      </div>

      <!-- 内嵌任务卡：生成后直接在消息流内展示 -->
      <TaskCardInline v-if="taskCard" :id="taskCard.id" :title="taskCard.title" />
      <div v-else-if="taskCardError" class="tc-error">{{ taskCardError }}</div>

      <div v-if="clarifyOptions.length" class="clarify-row">
        <button v-for="(o, i) in clarifyOptions" :key="i" class="chip" @click="$emit('clarify', o)">{{ o.label }}</button>
      </div>

      <div v-if="isError" class="ai-error">
        {{ errorMsg || '运行出错' }}
        <button class="btn retry-btn" @click="$emit('retry')">重试</button>
      </div>

      <div v-if="isDone" class="ai-footer">
        <div class="pill-row">
          <span v-if="capabilityName" class="pill cap-pill" :title="'调用能力：' + capabilityName">{{ capabilityName }}</span>
          <span v-if="toolCallCount" class="pill">{{ toolCallCount }} 次工具调用</span>
          <span v-if="rowCount != null" class="pill">{{ rowCount }} 行数据</span>
          <span v-if="elapsedMs != null" class="pill">耗时 {{ elapsedMs }}ms</span>
          <span v-if="dataAsOf" class="pill">数据截至 {{ dataAsOf }}</span>
        </div>
        <div class="action-row">
          <button class="icon-btn" :title="copied ? '已复制' : '复制'" @click="copyAnswer">
            <svg viewBox="0 0 24 24" width="15" height="15" fill="none" stroke="currentColor" stroke-width="2"><rect x="9" y="9" width="12" height="12" rx="2"/><path d="M5 15V5a2 2 0 0 1 2-2h10"/></svg>
          </button>
          <button class="icon-btn" title="重新生成" @click="$emit('retry')">
            <svg viewBox="0 0 24 24" width="15" height="15" fill="none" stroke="currentColor" stroke-width="2"><path d="M21 12a9 9 0 1 1-2.64-6.36M21 3v6h-6"/></svg>
          </button>
          <button v-if="hasResult && !taskCard" class="icon-btn" title="生成任务卡" @click="createTaskCard">
            <svg viewBox="0 0 24 24" width="15" height="15" fill="none" stroke="currentColor" stroke-width="2"><rect x="4" y="4" width="16" height="16" rx="2"/><path d="M8 9h8M8 13h5"/></svg>
          </button>
          <button class="icon-btn" :class="{ voted: feedbackGiven === 1 }" title="有用" @click="vote(1)">
            <svg viewBox="0 0 24 24" width="15" height="15" fill="none" stroke="currentColor" stroke-width="2"><path d="M7 22V11L12 2l2 7h6a2 2 0 0 1 2 2.2l-1.4 8A2 2 0 0 1 18.6 21H7z"/></svg>
          </button>
          <button class="icon-btn" :class="{ voted: feedbackGiven === -1 }" title="没用" @click="vote(-1)">
            <svg viewBox="0 0 24 24" width="15" height="15" fill="none" stroke="currentColor" stroke-width="2"><path d="M17 2v11l-5 9-2-7H4a2 2 0 0 1-2-2.2l1.4-8A2 2 0 0 1 5.4 3H17z"/></svg>
          </button>
        </div>
      </div>
    </template>
  </div>
</template>

<script setup>
const props = defineProps({
  stream: { type: Object, required: true },
  question: { type: String, default: '' }
})
defineEmits(['retry', 'clarify'])

const phase = computed(() => props.stream.phase.value)
const tools = computed(() => props.stream.tools.value)
const answer = computed(() => props.stream.answer.value)
const result = computed(() => props.stream.result.value)
const errorMsg = computed(() => props.stream.errorMsg.value)
const feedbackGiven = computed(() => props.stream.feedbackGiven.value)
const toolCallCount = computed(() => props.stream.toolCallCount.value)
const thinkingText = computed(() => props.stream.thinkingText ? props.stream.thinkingText.value : '')
const thinkingActive = computed(() => props.stream.thinkingActive ? props.stream.thinkingActive.value : false)

const isDone = computed(() => phase.value === 'done' || phase.value === 'cancelled')
const isError = computed(() => phase.value === 'error')
const cancelled = computed(() => phase.value === 'cancelled')
const hasResult = computed(() => !!result.value)

// 思考折叠：流式中自动展开，结束自动折叠
const thinkOpen = ref(false)
watch(thinkingActive, (v) => { thinkOpen.value = !!v })

const thinkBodyRef = ref(null)
// 思考分节：【理解问题】等标题行高亮；【取证回执 · xxx】按前缀归组
const SEG_ICONS = {
  '理解问题': '🔍',
  '路由决策': '📍',
  '开始执行': '⚡',
  '深度分析': '🧠'
}
const thinkSegments = computed(() => {
  const out = []
  for (const raw of (thinkingText.value || '').split('\n')) {
    const line = raw.trim()
    if (!line) { out.push({ kind: 'gap' }); continue }
    const m = line.match(/^【(.+?)】$/)
    if (m) {
      const t = m[1]
      const icon = SEG_ICONS[t] || (t.startsWith('取证回执') ? '📥' : '•')
      out.push({ kind: 'title', text: t, icon })
    } else {
      out.push({ kind: 'line', text: line, receipt: line.startsWith('·') })
    }
  }
  return out
})

// 关键数字卡片：结果 ≤4 行且含数值列时，从查询结果提取 卡片（label + value + unit）
const metricCards = computed(() => {
  const r = result.value
  if (!r || !Array.isArray(r.columns) || !Array.isArray(r.rows)) return []
  if (!r.rows.length || r.rows.length > 4) return []
  const col = i => r.columns[i] || {}
  let labelIdx = r.columns.findIndex(c => c.semantic === 'category')
  if (labelIdx < 0) labelIdx = 0
  let metricIdx = r.columns.findIndex(c => c.semantic === 'metric')
  if (metricIdx < 0) {
    // 兜底：第一列之外找数值型单元格
    for (let i = 0; i < r.columns.length; i++) {
      if (i === labelIdx) continue
      const v = r.rows[0][i]
      if (v != null && !isNaN(parseFloat(v))) { metricIdx = i; break }
    }
  }
  if (metricIdx < 0 || metricIdx === labelIdx) return []
  return r.rows.map(row => ({
    label: String(row[labelIdx] != null ? row[labelIdx] : ''),
    value: row[metricIdx] != null ? row[metricIdx] : '—',
    unit: col(metricIdx).unit || ''
  })).filter(c => c.label)
})
// 流式中自动跟随滚动到最新思考
watch(thinkingText, () => {
  if (thinkingActive.value && thinkBodyRef.value) {
    requestAnimationFrame(() => { thinkBodyRef.value.scrollTop = thinkBodyRef.value.scrollHeight })
  }
})

// 消息折叠：完成且长回答时可收起为摘要行
const collapsed = ref(false)
const canCollapse = computed(() => isDone.value && answer.value && answer.value.length > 600)

const phaseText = computed(() => {
  switch (phase.value) {
    case 'understanding': return '正在理解问题'
    case 'calling': return callingToolName.value ? '正在调用：' + callingToolName.value : '正在调用能力'
    case 'answering': return '正在整理回答'
    default: return ''
  }
})

// 当前执行中的能力名（calling 态头部显示）
const callingToolName = computed(() => {
  const running = tools.value.find(t => t.status === 'running')
  return running ? running.name : ''
})

// footer 能力徽章：最近一次调用的能力中文名
const capabilityName = computed(() => {
  if (!tools.value.length) return ''
  return tools.value[tools.value.length - 1].name || ''
})

const rowCount = computed(() => result.value && result.value.rowCount != null ? result.value.rowCount : null)
const elapsedMs = computed(() => result.value && result.value.stats ? result.value.stats.elapsedMs : null)
const dataAsOf = computed(() => result.value && result.value.freshness ? result.value.freshness.dataAsOf : null)

const clarifyOptions = computed(() => {
  const c = props.stream.clarify.value
  return c && Array.isArray(c.options) ? c.options : []
})

const copied = ref(false)

// 结论 Markdown 渲染（**粗体**/有序无序列表/标题/代码；html:false 防 XSS）
const { render: renderMd } = useMarkdown()
const answerHtml = ref('')
watch(answer, async v => {
  answerHtml.value = v ? await renderMd(v) : ''
}, { immediate: true })

function copyAnswer() {
  if (!answer.value) return
  navigator.clipboard.writeText(answer.value).then(() => {
    copied.value = true
    setTimeout(() => { copied.value = false }, 1500)
  }).catch(() => {})
}

function vote(rating) {
  props.stream.rate(rating)
}

// 内嵌任务卡：直接调后端创建，成功后消息内渲染（不再跳页）
const taskCard = ref(null)
const taskCardError = ref('')
async function createTaskCard() {
  taskCardError.value = ''
  try {
    const res = props.stream.result.value || {}
    const last = tools.value.length ? tools.value[tools.value.length - 1] : null
    const rawArgs = last && last.args && typeof last.args === 'object' ? last.args : {}
    const params = {}
    Object.keys(rawArgs).filter(k => !k.startsWith('_') && !k.startsWith('acl_')).forEach(k => { params[k] = rawArgs[k] })
    // capabilityId 传工具中文名（后端 TaskCardService 按中文名映射 capability 目录；
    // copilot 内部 id 如 real_alarm_count 与 capability id 不同体系，直传会 404）
    const toolName = last && last.name ? String(last.name) : ''
    const { create } = useTaskCards()
    const created = await create({
      title: props.question || res.capabilityId || '任务卡',
      capabilityId: toolName || res.capabilityId || '',
      params,
      conclusion: props.stream.answer.value || '',
      runId: props.stream.runId.value || '',
      traceId: props.stream.traceId.value || ''
    })
    taskCard.value = { id: created.id, title: created.title || props.question }
  } catch (e) {
    taskCardError.value = '任务卡生成失败：' + (e.message || e)
  }
}
</script>

<style scoped>
.ai-msg { margin: 0 0 28px; }
.ai-head { display: flex; align-items: center; gap: 10px; margin-bottom: 10px; }
.ai-avatar {
  width: 28px; height: 28px; border-radius: 50%;
  background: rgba(23, 160, 94, .14); color: var(--green-deep); font-size: 12px; font-weight: 700;
  display: inline-flex; align-items: center; justify-content: center;
}
.ai-name { font-size: 14px; font-weight: 600; color: var(--text-1); }
.ai-phase { display: inline-flex; align-items: center; gap: 6px; font-size: 12px; color: var(--text-3); }
.collapse-btn {
  margin-left: auto; height: 24px; padding: 0 10px; border-radius: 999px;
  border: 1px solid var(--border-default); background: #fff; color: var(--text-3);
  font: inherit; font-size: 11px; cursor: pointer;
}
.collapse-btn:hover { color: var(--text-1); border-color: var(--border-strong); }
/* 思考折叠块（对齐 Office_Agent 浅底卡：#FAFBFC + 细边框 + 12px 圆角） */
.think-block {
  margin: 0 0 10px;
  background: #FAFBFC; border: 1px solid var(--border-light, #F3F4F6); border-radius: 12px;
}
.think-toggle {
  display: flex; align-items: center; gap: 7px; width: 100%;
  padding: 8px 14px; border: none; background: none; color: var(--text-3);
  font: inherit; font-size: 12px; cursor: pointer; text-align: left;
}
.think-toggle:hover { color: var(--green-ink); }
.think-caret { margin-left: auto; transition: transform .15s; }
.think-caret.up { transform: rotate(180deg); }
.think-body {
  max-height: 300px; overflow-y: auto; padding: 2px 14px 12px;
}
.seg-title {
  display: flex; align-items: center; gap: 6px;
  font-size: 12px; font-weight: 600; color: var(--text-3);
  text-transform: uppercase; letter-spacing: .5px; margin: 10px 0 4px;
}
.seg-title:first-child { margin-top: 2px; }
.seg-icon { font-size: 13px; }
.seg-gap { height: 4px; }
.seg-line {
  font-size: 12.5px; line-height: 1.8; color: var(--text-2, #4B5563);
  white-space: pre-wrap; word-break: break-word; padding-left: 19px;
}
/* 取证回执行（· 开头）：等宽数字 + 稍浅 */
.seg-line.receipt { font-variant-numeric: tabular-nums; color: var(--text-secondary, #6B7280); }
/* 关键数字卡片行 */
.metric-cards { display: flex; flex-wrap: wrap; gap: 10px; margin: 12px 0 4px; }
.metric-card {
  min-width: 108px; max-width: 180px; flex: 1;
  padding: 10px 14px; border-radius: 12px;
  background: #FAFBFC; border: 1px solid var(--border-light, #F3F4F6);
}
.mc-value { font-size: 22px; font-weight: 600; color: var(--text-1); font-variant-numeric: tabular-nums; line-height: 1.2; }
.mc-unit { font-size: 12px; font-weight: 400; color: var(--text-3); margin-left: 3px; }
.mc-label {
  font-size: 12px; color: var(--text-3); margin-top: 4px;
  overflow: hidden; text-overflow: ellipsis; white-space: nowrap;
}
/* 折叠态摘要 */
.collapsed-summary {
  padding: 10px 14px; border: 1px dashed var(--border-default); border-radius: 10px;
  font-size: 12.5px; color: var(--text-2); cursor: pointer;
  white-space: nowrap; overflow: hidden; text-overflow: ellipsis;
}
.collapsed-summary:hover { border-color: var(--green-deep); color: var(--text-1); }
.cs-meta { color: var(--text-3); font-size: 11px; margin-left: 8px; }
.tc-error { font-size: 12px; color: var(--danger); margin: 8px 0; }
.answer-label {
  font-size: 11px; font-weight: 500; letter-spacing: 2px;
  color: var(--text-3); margin: 4px 0 8px;
}
.ai-answer {
  font-size: 14px; line-height: 1.9; color: var(--text-1);
  white-space: pre-wrap; word-break: break-word;
}
/* Markdown 结论排版：段落/加粗/列表/代码（对齐 Office_Agent 克制风） */
.ai-answer.md { white-space: normal; }
.ai-answer.md :deep(p) { margin: 0 0 10px; }
.ai-answer.md :deep(p:last-child) { margin-bottom: 0; }
.ai-answer.md :deep(strong) { font-weight: 600; color: var(--text-1); }
.ai-answer.md :deep(ol), .ai-answer.md :deep(ul) { margin: 4px 0 10px; padding-left: 22px; }
.ai-answer.md :deep(li) { margin: 3px 0; }
.ai-answer.md :deep(h1), .ai-answer.md :deep(h2), .ai-answer.md :deep(h3) {
  font-size: 14.5px; font-weight: 600; margin: 12px 0 6px;
}
.ai-answer.md :deep(code) {
  font-family: var(--f-mono, "SF Mono", Consolas, monospace); font-size: 12.5px;
  background: var(--bg-input, #f3f4f6); border-radius: 4px; padding: 1px 5px;
}
.ai-answer.md :deep(a) { color: var(--primary); text-decoration: underline; }
.ai-answer.md :deep(hr) { border: none; border-top: 1px solid var(--border-light); margin: 10px 0; }
.ai-answer.md :deep(blockquote) {
  margin: 6px 0; padding: 2px 12px; border-left: 2px solid var(--border-strong);
  color: var(--text-2);
}
.cancelled-mark { color: var(--text-3); font-size: 12px; }
.ai-error { color: var(--danger); font-size: 13px; display: flex; align-items: center; gap: 10px; }
.retry-btn { padding: 3px 10px; font-size: 12px; }
.clarify-row { display: flex; flex-wrap: wrap; gap: 8px; margin: 10px 0; }
.chip {
  border: 1px solid var(--border-default); background: #fff;
  color: var(--primary); border-radius: 999px; padding: 6px 14px;
  font-size: 12px; cursor: pointer; transition: all .15s ease;
}
.chip:hover { border-color: var(--primary); background: var(--primary-light); }
.ai-footer { margin-top: 14px; }
.pill-row { display: flex; flex-wrap: wrap; gap: 6px; margin-bottom: 10px; }
.pill {
  font-size: 11px; color: var(--text-2);
  background: var(--bg-input); border-radius: 999px; padding: 3px 10px;
}
.cap-pill {
  color: var(--green-ink); background: rgba(23, 160, 94, .1);
  max-width: 200px; overflow: hidden; text-overflow: ellipsis; white-space: nowrap;
}
.action-row { display: flex; gap: 4px; }
.icon-btn {
  width: 28px; height: 28px; display: inline-flex; align-items: center; justify-content: center;
  border: 1px solid var(--border-default); background: #fff; color: var(--text-3);
  border-radius: 6px; cursor: pointer; transition: all .15s ease;
}
.icon-btn:hover { background: var(--bg-hover); color: var(--text-1); border-color: var(--border-strong); }
.icon-btn.voted { color: var(--primary); border-color: var(--primary); background: var(--primary-light); }
</style>

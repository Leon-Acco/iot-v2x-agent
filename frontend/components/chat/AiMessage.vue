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
          <div v-else class="seg-line">{{ seg.text }}</div>
        </template>
      </div>
    </div>

    <div v-if="collapsed" class="collapsed-summary" @click="collapsed = false">
      {{ answer.slice(0, 50) }}{{ answer.length > 50 ? '…' : '' }}
      <span class="cs-meta">{{ toolCallCount }} 次调用</span>
    </div>

    <template v-else>
      <ToolCallNarrative :tools="tools" />

      <template v-if="answer">
        <div class="answer-label">结论</div>
        <div class="ai-answer">{{ answer }}<span v-if="cancelled" class="cancelled-mark">（已手动停止生成）</span></div>
      </template>
      <div v-else-if="cancelled" class="ai-answer cancelled-mark">（已手动停止生成）</div>

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
// 思考分节：【理解问题】等标题行高亮，其余为正文行
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
    if (m) out.push({ kind: 'title', text: m[1], icon: SEG_ICONS[m[1]] || '•' })
    else out.push({ kind: 'line', text: line })
  }
  return out
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
    const { create } = useTaskCards()
    const created = await create({
      title: props.question || res.capabilityId || '任务卡',
      capabilityId: res.capabilityId || (last && last.id) || '',
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
/* 思考折叠块 */
.think-block {
  margin: 0 0 10px; border-left: 2px solid rgba(23, 160, 94, .35);
  background: rgba(23, 160, 94, .04); border-radius: 0 10px 10px 0;
}
.think-toggle {
  display: flex; align-items: center; gap: 7px; width: 100%;
  padding: 7px 12px; border: none; background: none; color: var(--text-3);
  font: inherit; font-size: 12px; cursor: pointer; text-align: left;
}
.think-toggle:hover { color: var(--green-ink); }
.think-caret { margin-left: auto; transition: transform .15s; }
.think-caret.up { transform: rotate(180deg); }
.think-body {
  max-height: 260px; overflow-y: auto; padding: 2px 14px 12px;
}
.seg-title {
  display: flex; align-items: center; gap: 6px;
  font-size: 12px; font-weight: 600; color: var(--green-deep, #0b6e55);
  letter-spacing: 1px; margin: 10px 0 4px;
}
.seg-title:first-child { margin-top: 2px; }
.seg-icon { font-size: 13px; }
.seg-gap { height: 4px; }
.seg-line {
  font-size: 12.5px; line-height: 1.85; color: var(--text-2, #475569);
  white-space: pre-wrap; word-break: break-word; padding-left: 19px;
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

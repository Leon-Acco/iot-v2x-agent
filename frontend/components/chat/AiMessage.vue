<template>
  <!-- AI 消息：无气泡融入背景，头像+名称头部、「结论」标签、pill 徽章、方形图标操作组 -->
  <div class="ai-msg">
    <div class="ai-head">
      <span class="ai-avatar">A</span>
      <span class="ai-name">设备运营 Agent</span>
      <span v-if="phaseText" class="ai-phase">{{ phaseText }}<ThinkingDots /></span>
    </div>

    <ToolCallNarrative :tools="tools" />


    <template v-if="answer">
      <div class="answer-label">结论</div>
      <div class="ai-answer">{{ answer }}<span v-if="cancelled" class="cancelled-mark">（已手动停止生成）</span></div>
    </template>
    <div v-else-if="cancelled" class="ai-answer cancelled-mark">（已手动停止生成）</div>

    <div v-if="clarifyOptions.length" class="clarify-row">
      <button v-for="(o, i) in clarifyOptions" :key="i" class="chip" @click="$emit('clarify', o)">{{ o.label }}</button>
    </div>

    <div v-if="isError" class="ai-error">
      {{ errorMsg || '运行出错' }}
      <button class="btn retry-btn" @click="$emit('retry')">重试</button>
    </div>

    <div v-if="isDone" class="ai-footer">
      <div class="pill-row">
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
        <button v-if="hasResult" class="icon-btn" title="生成任务卡" @click="$emit('taskcard')">
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
  </div>
</template>

<script setup>
const props = defineProps({
  stream: { type: Object, required: true }
})
defineEmits(['retry', 'clarify', 'taskcard'])

const phase = computed(() => props.stream.phase.value)
const tools = computed(() => props.stream.tools.value)
const answer = computed(() => props.stream.answer.value)
const result = computed(() => props.stream.result.value)
const errorMsg = computed(() => props.stream.errorMsg.value)
const feedbackGiven = computed(() => props.stream.feedbackGiven.value)
const toolCallCount = computed(() => props.stream.toolCallCount.value)

const isDone = computed(() => phase.value === 'done' || phase.value === 'cancelled')
const isError = computed(() => phase.value === 'error')
const cancelled = computed(() => phase.value === 'cancelled')
const hasResult = computed(() => !!result.value)

const phaseText = computed(() => {
  switch (phase.value) {
    case 'understanding': return '正在理解问题'
    case 'calling': return '正在调用能力'
    case 'answering': return '正在整理回答'
    default: return ''
  }
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
</script>

<style scoped>
.ai-msg { margin: 0 0 28px; }
.ai-head { display: flex; align-items: center; gap: 10px; margin-bottom: 10px; }
.ai-avatar {
  width: 28px; height: 28px; border-radius: 50%;
  background: var(--bg-dark); color: #fff; font-size: 12px; font-weight: 600;
  display: inline-flex; align-items: center; justify-content: center;
}
.ai-name { font-size: 14px; font-weight: 600; color: var(--text-1); }
.ai-phase { display: inline-flex; align-items: center; gap: 6px; font-size: 12px; color: var(--text-3); }
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
.action-row { display: flex; gap: 4px; }
.icon-btn {
  width: 28px; height: 28px; display: inline-flex; align-items: center; justify-content: center;
  border: 1px solid var(--border-default); background: #fff; color: var(--text-3);
  border-radius: 6px; cursor: pointer; transition: all .15s ease;
}
.icon-btn:hover { background: var(--bg-hover); color: var(--text-1); border-color: var(--border-strong); }
.icon-btn.voted { color: var(--primary); border-color: var(--primary); background: var(--primary-light); }
</style>

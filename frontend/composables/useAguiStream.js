// AG-UI 流式状态机：understanding -> calling -> answering -> done / error / cancelled
// 三区联动单一事实源：result = 最近一次 TOOL_CALL_RESULT
export function useAguiStream() {
  const phase = ref('idle')
  const tools = ref([])
  const answer = ref('')
  const result = ref(null)
  const runId = ref('')
  const traceId = ref('')
  const errorMsg = ref('')
  const feedbackGiven = ref(0)
  const clarify = ref(null)
  const followUpSuggestions = ref([])

  let handle = null
  let pendingDelta = ''
  let flushScheduled = false

  function reset() {
    phase.value = 'idle'
    tools.value = []
    answer.value = ''
    result.value = null
    runId.value = ''
    traceId.value = ''
    errorMsg.value = ''
    feedbackGiven.value = 0
    clarify.value = null
    followUpSuggestions.value = []
    pendingDelta = ''
    flushScheduled = false
  }

  // 90ms 节流 + rAF 渲染，避免流式刷新卡顿
  function scheduleFlush() {
    if (flushScheduled) return
    flushScheduled = true
    setTimeout(() => {
      requestAnimationFrame(() => {
        answer.value += pendingDelta
        pendingDelta = ''
        flushScheduled = false
      })
    }, 90)
  }

  function lastTool() {
    return tools.value.length ? tools.value[tools.value.length - 1] : null
  }

  function onEvent(type, payload) {
    switch (type) {
      case 'RUN_STARTED':
        runId.value = payload.runId || runId.value
        phase.value = 'understanding'
        break
      case 'STEP_STARTED':
        if (phase.value === 'idle') phase.value = 'understanding'
        break
      case 'TOOL_CALL_START':
        phase.value = 'calling'
        tools.value.push({
          id: payload.capabilityId,
          name: payload.displayName || payload.capabilityId,
          status: 'running',
          args: null,
          summary: '',
          result: null
        })
        break
      case 'TOOL_CALL_ARGS': {
        const t = lastTool()
        if (t) t.args = payload.args
        break
      }
      case 'TOOL_CALL_RESULT': {
        const t = lastTool()
        const hasError = payload && payload.error
        if (t) {
          t.status = hasError ? 'error' : 'done'
          t.result = payload
          if (payload && payload.rowCount != null) {
            t.summary = '→ 找到 ' + payload.rowCount + ' 条'
          }
        }
        if (payload && payload.columns) result.value = payload
        break
      }
      case 'TEXT_MESSAGE_CONTENT':
        phase.value = 'answering'
        pendingDelta += payload.delta || ''
        scheduleFlush()
        break
      case 'RUN_FINISHED':
        if (pendingDelta) { answer.value += pendingDelta; pendingDelta = '' }
        if (payload && payload.traceId) traceId.value = payload.traceId
        if (payload && Array.isArray(payload.followUps) && payload.followUps.length) {
          followUpSuggestions.value = payload.followUps
        }
        phase.value = 'done'
        break
      case 'REFUSE':
        // 拒答帧：展示原因说明，建议能力转为追问 chips
        if (payload && payload.message) {
          pendingDelta += payload.message
          scheduleFlush()
        }
        if (payload && Array.isArray(payload.suggestedCapabilities)) {
          followUpSuggestions.value = payload.suggestedCapabilities
            .map(c => c && c.display ? c.display : '')
            .filter(Boolean)
        }
        phase.value = 'answering'
        break
      case 'CHART_SPEC':
        // 图表规格帧：合并进 result，供 ChartPanel 渲染
        result.value = Object.assign({}, result.value || {}, { chart: payload })
        break
      case 'CLARIFY':
        // 澄清帧：把问题当回答展示，选项交给前端渲染
        clarify.value = payload
        if (payload && payload.question) {
          pendingDelta += payload.question
          scheduleFlush()
        }
        phase.value = 'answering'
        break
      case 'RUN_ERROR':
        phase.value = 'error'
        errorMsg.value = (payload && payload.message) || '运行出错'
        break
      default:
        break
    }
  }

  function start(question, profileId, threadId) {
    reset()
    phase.value = 'understanding'
    handle = runAgui({ question, profileId: profileId || 'device_ops', threadId }, {
      onEvent,
      onDone() {
        if (pendingDelta) { answer.value += pendingDelta; pendingDelta = '' }
        if (phase.value !== 'error' && phase.value !== 'cancelled') phase.value = 'done'
      },
      onError(err) {
        phase.value = 'error'
        errorMsg.value = err && err.message ? err.message : '运行出错'
      }
    })
    runId.value = handle.runId
    return handle.runId
  }

  // 中断：断流 + 通知后端，已生成内容保留
  function cancel() {
    if (handle) handle.abort()
    if (pendingDelta) { answer.value += pendingDelta; pendingDelta = '' }
    if (phase.value !== 'done' && phase.value !== 'error') phase.value = 'cancelled'
  }

  // 参数微调：不走模型，直接重跑执行器并覆盖 result
  async function rerun(capabilityId, params) {
    const data = await toolResult(capabilityId, params)
    if (data && data.columns) result.value = data
    return data
  }

  // 赞/踩反馈，防重复提交
  async function rate(rating) {
    if (feedbackGiven.value) return
    feedbackGiven.value = rating
    try {
      await sendFeedback(runId.value, traceId.value, rating)
    } catch (e) { /* 反馈失败不影响主流程 */ }
  }

  const toolCallCount = computed(() => tools.value.length)
  const isRunning = computed(() => ['understanding', 'calling', 'answering'].includes(phase.value))

  return {
    phase, tools, answer, result, runId, traceId, errorMsg, feedbackGiven,
    clarify, followUpSuggestions,
    toolCallCount, isRunning,
    start, cancel, rerun, rate
  }
}

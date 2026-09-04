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
  const visualizations = ref([])
  const traceSteps = ref([])
  // 思考过程（THINKING 帧：GLM reasoning_content 流式增量）
  const thinkingText = ref('')
  const thinkingActive = ref(false)

  let handle = null
  let pendingDelta = ''
  let flushScheduled = false
  let pendingThink = ''
  let thinkFlushScheduled = false
  // 产物收集钩子：页面层注入，用于把会话内产物（result/chart/viz）按轮次累积进工作台流水账
  let artifactHook = null

  function setArtifactHook(cb) {
    artifactHook = typeof cb === 'function' ? cb : null
  }

  function notifyArtifact(kind, payload, tool) {
    if (artifactHook) {
      artifactHook({
        kind,
        payload,
        tool: tool ? { id: tool.id, name: tool.name, toolName: tool.toolName || null } : null
      })
    }
  }

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
    visualizations.value = []
    traceSteps.value = []
    thinkingText.value = ''
    thinkingActive.value = false
    pendingDelta = ''
    flushScheduled = false
    pendingThink = ''
    thinkFlushScheduled = false
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

  // 思考文本节流（同 answer 模式）
  function scheduleThinkFlush() {
    if (thinkFlushScheduled) return
    thinkFlushScheduled = true
    setTimeout(() => {
      requestAnimationFrame(() => {
        thinkingText.value += pendingThink
        pendingThink = ''
        thinkFlushScheduled = false
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
          toolName: payload.toolName || null,
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
        // 产物钩子：查询结果表（带能力标注）
        if (payload && payload.columns) notifyArtifact('result', payload, t)
        break
      }
      case 'TEXT_MESSAGE_CONTENT':
        phase.value = 'answering'
        pendingDelta += payload.delta || ''
        scheduleFlush()
        break
      case 'THINKING':
        // 思考帧：phase=start/delta/end，delta 流式追加
        if (payload && payload.phase === 'start') {
          thinkingActive.value = true
        } else if (payload && payload.phase === 'delta') {
          pendingThink += payload.delta || ''
          scheduleThinkFlush()
        } else if (payload && payload.phase === 'end') {
          if (pendingThink) { thinkingText.value += pendingThink; pendingThink = '' }
          thinkingActive.value = false
        }
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
      case 'AGENT_TRACE':
        // Agent 轨迹帧：执行步骤流（类型/名称/耗时/状态）
        if (payload && Array.isArray(payload.steps)) traceSteps.value = payload.steps
        break
      case 'VIS_SPEC':
        // UI Schema frame: inline visualization rendered by VisualizationRenderer
        if (payload && payload.type === 'visualization') {
          visualizations.value.push(payload)
          // 产物钩子：可视化卡（payload.tool 为生成工具标注）
          notifyArtifact('viz', payload, lastTool())
        }
        break
      case 'CHART_SPEC':
        // 图表规格帧：合并进 result，供 ChartPanel 渲染
        result.value = Object.assign({}, result.value || {}, { chart: payload })
        // 产物钩子：结果图表（帧上带 capabilityId/capabilityDisplay 标注）
        notifyArtifact('chart', payload, lastTool())
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

  // opts.history: 多轮上下文 [{role, content}]；opts.forcedTool: 用户指定工具 id
  function start(question, profileId, threadId, opts) {
    reset()
    phase.value = 'understanding'
    const o = opts || {}
    handle = runAgui({
      question,
      profileId: profileId || 'fleet_copilot',
      threadId,
      history: o.history,
      forcedTool: o.forcedTool
    }, {
      onEvent,
      onDone() {
        if (pendingDelta) { answer.value += pendingDelta; pendingDelta = '' }
        if (pendingThink) { thinkingText.value += pendingThink; pendingThink = '' }
        thinkingActive.value = false
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
    if (pendingThink) { thinkingText.value += pendingThink; pendingThink = '' }
    thinkingActive.value = false
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
    clarify, followUpSuggestions, visualizations, traceSteps,
    thinkingText, thinkingActive,
    toolCallCount, isRunning,
    start, cancel, rerun, rate, onEvent, setArtifactHook
  }
}

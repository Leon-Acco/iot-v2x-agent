// AG-UI SSE 协议客户端：帧归一化 + 流解析（移植自旧 agui.js，协议不变）
const LF = String.fromCharCode(10)
const CR = String.fromCharCode(13)

function tryParse(s) {
  if (s == null) return null
  if (typeof s !== 'string') return s
  try { return JSON.parse(s) } catch (e) { return null }
}

// 帧归一化：原始帧 -> [type, payload]
export function normalizeFrame(frame) {
  switch (frame.type) {
    case 'RUN_STARTED':
      return ['RUN_STARTED', { runId: frame.runId, threadId: frame.threadId }]
    case 'STEP_STARTED':
      return ['STEP_STARTED', { stage: frame.stepName }]
    case 'STEP_FINISHED':
      return ['STEP_FINISHED', { stage: frame.stepName }]
    case 'TOOL_CALL_START':
      return ['TOOL_CALL_START', { capabilityId: frame.toolCallId, displayName: frame.toolCallName }]
    case 'TOOL_CALL_ARGS':
      return ['TOOL_CALL_ARGS', { args: tryParse(frame.delta) }]
    case 'TOOL_CALL_RESULT':
      return ['TOOL_CALL_RESULT', tryParse(frame.content) || {}]
    case 'TEXT_MESSAGE_CONTENT':
      return ['TEXT_MESSAGE_CONTENT', { delta: frame.delta || '' }]
    case 'RUN_FINISHED':
      return ['RUN_FINISHED', frame.result || {}]
    case 'RUN_ERROR':
      return ['RUN_ERROR', { message: frame.message, errorCode: frame.code }]
    case 'CUSTOM':
      return [frame.name, frame.value || {}]
    default:
      return null
  }
}

// SSE 流解析：45s 无帧看门狗，data: 多行拼接，\r\n 兼容
export async function parseStream(reader, onFrame) {
  const decoder = new TextDecoder('utf-8')
  let buffer = ''
  let curData = ''
  let lastFrameAt = Date.now()
  const watchdog = setInterval(() => {
    if (Date.now() - lastFrameAt > 45000) {
      clearInterval(watchdog)
      try { reader.cancel() } catch (e) { /* ignore */ }
    }
  }, 5000)
  const markFrame = () => { lastFrameAt = Date.now() }
  const flush = () => {
    if (!curData) return
    try {
      const norm = normalizeFrame(JSON.parse(curData))
      if (norm) onFrame(norm[0], norm[1])
    } catch (e) { /* 单帧解析失败跳过 */ }
    curData = ''
  }
  while (true) {
    const { done, value } = await reader.read()
    if (done) break
    buffer += decoder.decode(value, { stream: true })
    const lines = buffer.split(LF)
    buffer = lines.pop()
    for (const rawLine of lines) {
      const line = rawLine.endsWith(CR) ? rawLine.slice(0, -1) : rawLine
      if (line === '') flush()
      else if (line.startsWith('data:')) {
        markFrame()
        curData += (curData ? LF : '') + line.substring(5).trim()
      }
    }
  }
  clearInterval(watchdog)
  flush()
}

// 发起一次 AG-UI 运行，返回 { runId, abort }
export function runAgui(req, handlers) {
  const controller = new AbortController()
  const runId = (crypto.randomUUID ? crypto.randomUUID() : String(Date.now())).replace(/-/g, '')
  const profileId = req.profileId || 'device_ops'
  const body = {
    threadId: req.threadId || runId,
    runId,
    messages: [{ id: runId + '-u', role: 'user', content: req.question }]
  }

  ;(async () => {
    try {
      const resp = await fetch('/agui/run/' + encodeURIComponent(profileId), {
        method: 'POST',
        headers: { 'Content-Type': 'application/json' },
        body: JSON.stringify(body),
        signal: controller.signal
      })
      if (resp.status === 401) {
        window.location.href = '/login'
        return
      }
      if (!resp.ok || !resp.body) {
        handlers.onError(new Error('HTTP ' + resp.status))
        return
      }
      await parseStream(resp.body.getReader(), (type, payload) => handlers.onEvent(type, payload))
      handlers.onDone()
    } catch (err) {
      if (err.name === 'AbortError') handlers.onDone()
      else handlers.onError(err)
    }
  })()

  return {
    runId,
    abort() {
      controller.abort()
      fetch('/ag-ui/run/' + runId + '/cancel', { method: 'POST' }).catch(() => {})
    }
  }
}

// 参数微调重跑：直接调执行器，不走模型
export async function toolResult(capabilityId, params) {
  const resp = await fetch('/ag-ui/tool-result', {
    method: 'POST',
    headers: { 'Content-Type': 'application/json' },
    body: JSON.stringify({ capabilityId, params })
  })
  if (!resp.ok) throw new Error('HTTP ' + resp.status)
  return resp.json()
}

// 反馈入库（赞/踩）
export async function sendFeedback(runId, traceId, rating, comment) {
  await fetch('/ag-ui/feedback', {
    method: 'POST',
    headers: { 'Content-Type': 'application/json' },
    body: JSON.stringify({ runId, traceId, rating, comment: comment || '' })
  })
}

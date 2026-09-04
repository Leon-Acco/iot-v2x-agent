// chat-workspace 插件的 SSR 安全包装：
// server 分支返回惰性空壳（不注册不报错），client 分支取插件实例。
export function useChatWorkspace() {
  if (import.meta.server) {
    return {
      streams: new Map(),
      activeThread: '',
      activeRunId: '',
      activeQuestion: '',
      live() { return null }
    }
  }
  const ws = useNuxtApp().$chatWorkspace
  return {
    streams: ws.streams,
    activeThread: ws.activeThread,
    activeRunId: ws.activeRunId,
    activeQuestion: ws.activeQuestion,
    /** 注册活跃流（send 时调用，question 供切页恢复时重建消息头） */
    track(messageId, stream, threadId, runId, question) {
      ws.streams.set(messageId, stream)
      ws.activeThread = threadId || ''
      ws.activeRunId = runId || ''
      ws.activeQuestion = question || ''
    },
    /** 结束时清理（phase done/error/cancelled） */
    untrack(messageId) {
      ws.streams.delete(messageId)
      if (!ws.streams.size) {
        ws.activeThread = ''
        ws.activeRunId = ''
        ws.activeQuestion = ''
      }
    }
  }
}

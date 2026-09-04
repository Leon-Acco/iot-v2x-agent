// 应用级聊天工作区（仅客户端）：
// 页面卸载后活跃流对象仍被 Map 引用不被 GC，fetch 闭包继续读 SSE；
// 页面重进时从这里把同一 stream 对象重新绑回消息，渲染自然接续。
// 纪律：不用 useState 存流对象（ssr:true 下会被序列化破坏闭包）。
export default defineNuxtPlugin((nuxtApp) => {
  const workspace = {
    // messageId -> markRaw(useAguiStream()) 实例（进行中的流）
    streams: new Map(),
    // 有活跃流的 threadId（重进页面时定位会话）
    activeThread: '',
    // 最近一次活跃 runId
    activeRunId: '',
    // 活跃流对应的用户问题（切页恢复时重建消息头）
    activeQuestion: ''
  }
  nuxtApp.provide('chatWorkspace', workspace)
})

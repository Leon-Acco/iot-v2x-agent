// 通用轮询: 页载启动, 卸载清理; 隁面隐藏时暂停; 连续失败指数退避
export function usePolling(fn, intervalMs) {
  let timer = null
  let failures = 0
  let stopped = false

  async function tick() {
    try {
      await fn()
      failures = 0
    } catch (e) {
      failures = Math.min(failures + 1, 4)
    }
    schedule()
  }

  function schedule() {
    if (stopped) return
    const backoff = failures === 0 ? 1 : Math.min(2 ** failures, 10)
    timer = setTimeout(tick, intervalMs * backoff)
  }

  function onVisibility() {
    if (document.hidden) {
      if (timer) clearTimeout(timer)
      timer = null
    } else if (!timer && !stopped) {
      tick()
    }
  }

  onMounted(() => {
    document.addEventListener('visibilitychange', onVisibility)
    schedule()
  })
  onBeforeUnmount(() => {
    stopped = true
    if (timer) clearTimeout(timer)
    document.removeEventListener('visibilitychange', onVisibility)
  })
}

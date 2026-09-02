// ECharts 封装：客户端动态导入，option 驱动，防止 SSR 破坏
export function useChart() {
  let chart = null

  async function mount(el) {
    if (import.meta.server || !el) return
    const echarts = await import('echarts')
    if (!el.isConnected) return
    chart = echarts.init(el)
  }

  function setOption(option) {
    if (chart && option) chart.setOption(option, { notMerge: true })
  }

  function resize() {
    if (chart) chart.resize()
  }

  function dispose() {
    if (chart) { chart.dispose(); chart = null }
  }

  return { mount, setOption, resize, dispose }
}

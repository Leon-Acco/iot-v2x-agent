// chartType + columns + rows -> ECharts option
// 红线：不消费后端 vega-lite spec，只用 chartType 与表格数据自构 option
export const CHART_TYPE_LABELS = {
  bar: '柱状图',
  line: '折线图',
  area: '面积图',
  pie: '饼图',
  scatter: '散点图',
  table: '表格'
}

const PALETTE = ['#17A05E', '#E87A1E', '#0B7A3C', '#8AA294', '#2E9E6B', '#C89B5A']

function splitColumns(columns) {
  const cols = Array.isArray(columns) ? columns : []
  return {
    // time 语义列同样可作 x 轴（日期串进类目轴），修复时间序列 x 轴退化为序号的 bug
    categories: cols.filter(c => c && (c.semantic === 'category' || c.semantic === 'time')),
    metrics: cols.filter(c => c && c.semantic === 'metric')
  }
}

function toNumber(v) {
  const n = Number(v)
  return isNaN(n) ? 0 : n
}

export function buildChartOption(chartType, columns, rows) {
  const cols = Array.isArray(columns) ? columns : []
  const data = Array.isArray(rows) ? rows : []
  if (!cols.length || !data.length) return null
  const { categories, metrics } = splitColumns(cols)
  if (!metrics.length) return null
  const catIdx = categories.length ? cols.indexOf(categories[0]) : -1
  const base = { color: PALETTE, tooltip: { trigger: 'axis' } }

  if (chartType === 'pie') {
    const mIdx = cols.indexOf(metrics[0])
    return {
      color: PALETTE,
      tooltip: { trigger: 'item', formatter: '{b}: {c} ({d}%)' },
      legend: { bottom: 0, itemWidth: 10, itemHeight: 10, icon: 'circle', textStyle: { fontSize: 11, color: '#5C7266' } },
      series: [{
        type: 'pie', radius: ['40%', '66%'], center: ['50%', '46%'],
        itemStyle: { borderRadius: 4, borderColor: '#fff', borderWidth: 2 },
        label: { show: false },
        data: data.map(r => ({ name: catIdx >= 0 ? String(r[catIdx]) : '-', value: toNumber(r[mIdx]) }))
      }]
    }
  }

  if (chartType === 'scatter') {
    const xIdx = cols.indexOf(metrics[0])
    const yIdx = metrics.length > 1 ? cols.indexOf(metrics[1]) : xIdx
    return Object.assign({}, base, {
      tooltip: { trigger: 'item' },
      xAxis: { type: 'value', name: metrics[0].display || metrics[0].name },
      yAxis: { type: 'value', name: (metrics[1] || metrics[0]).display || '' },
      series: [{ type: 'scatter', symbolSize: 10, data: data.map(r => [toNumber(r[xIdx]), toNumber(r[yIdx])]) }]
    })
  }

  // bar / line / area 默认走类目轴；time 列先按值排序，保证日期序列单调不回折
  let sorted = data
  if (catIdx >= 0 && categories[0] && categories[0].semantic === 'time') {
    sorted = data.slice().sort((a, b) => String(a[catIdx]).localeCompare(String(b[catIdx])))
  }
  const xData = catIdx >= 0 ? sorted.map(r => String(r[catIdx])) : data.map((_, i) => String(i + 1))
  const series = metrics.map(m => {
    const mIdx = cols.indexOf(m)
    return {
      name: (m.display || m.name) + (m.unit ? '(' + m.unit + ')' : ''),
      type: chartType === 'bar' ? 'bar' : 'line',
      areaStyle: chartType === 'area' ? { opacity: 0.18 } : undefined,
      smooth: chartType !== 'bar',
      barMaxWidth: 36,
      data: sorted.map(r => toNumber(r[mIdx]))
    }
  })
  return Object.assign({}, base, {
    legend: { top: 0, textStyle: { fontSize: 11, color: '#5C7266' } },
    grid: { left: 8, right: 12, top: 34, bottom: 4, containLabel: true },
    xAxis: {
      type: 'category', data: xData,
      axisLabel: { fontSize: 10, color: '#8AA294', rotate: xData.length > 8 ? 30 : 0 }
    },
    yAxis: { type: 'value', axisLabel: { fontSize: 10, color: '#8AA294' }, splitLine: { lineStyle: { color: '#E3EDE7' } } },
    series
  })
}

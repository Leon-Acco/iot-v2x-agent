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

const PALETTE = ['#2B7FFF', '#38BDF8', '#F59E0B', '#22C55E', '#7FB5FF', '#A8CCFF']

function splitColumns(columns) {
  const cols = Array.isArray(columns) ? columns : []
  return {
    categories: cols.filter(c => c && c.semantic === 'category'),
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
      legend: { bottom: 0, itemWidth: 10, itemHeight: 10, icon: 'circle', textStyle: { fontSize: 11, color: '#5B6B7F' } },
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

  // bar / line / area 默认走类目轴
  const xData = catIdx >= 0 ? data.map(r => String(r[catIdx])) : data.map((_, i) => String(i + 1))
  const series = metrics.map(m => {
    const mIdx = cols.indexOf(m)
    return {
      name: (m.display || m.name) + (m.unit ? '(' + m.unit + ')' : ''),
      type: chartType === 'bar' ? 'bar' : 'line',
      areaStyle: chartType === 'area' ? { opacity: 0.18 } : undefined,
      smooth: chartType !== 'bar',
      barMaxWidth: 36,
      data: data.map(r => toNumber(r[mIdx]))
    }
  })
  return Object.assign({}, base, {
    legend: { top: 0, textStyle: { fontSize: 11, color: '#5B6B7F' } },
    grid: { left: 8, right: 12, top: 34, bottom: 4, containLabel: true },
    xAxis: {
      type: 'category', data: xData,
      axisLabel: { fontSize: 10, color: '#93A1B5', rotate: xData.length > 8 ? 30 : 0 }
    },
    yAxis: { type: 'value', axisLabel: { fontSize: 10, color: '#93A1B5' }, splitLine: { lineStyle: { color: '#EDF3FB' } } },
    series
  })
}

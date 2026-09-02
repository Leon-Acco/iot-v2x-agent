// 对象数组 -> CSV 下载（加 BOM 防 Excel 乱码，逗号/引号/换行转义）
export function downloadCsv(filename, headers, rows) {
  const esc = (v) => {
    const s = v == null ? '' : String(v)
    if (s.includes('"') || s.includes(',') || s.includes(String.fromCharCode(10))) {
      return '"' + s.split('"').join('""') + '"'
    }
    return s
  }
  const LF = String.fromCharCode(10)
  const lines = [headers.map(h => esc(h.label)).join(',')]
  for (const r of rows) {
    lines.push(headers.map(h => esc(r[h.key])).join(','))
  }
  const bom = String.fromCharCode(0xFEFF)
  const blob = new Blob([bom + lines.join(LF)], { type: 'text/csv;charset=utf-8' })
  const url = URL.createObjectURL(blob)
  const a = document.createElement('a')
  a.href = url
  a.download = filename
  a.click()
  URL.revokeObjectURL(url)
}

// 轻量 Markdown 渲染：结论区 **加粗** / 列表 / 标题 / 代码（LLM 输出常见格式）
// html:false 转义原始 HTML（防 XSS），breaks:true 单换行成 <br>，linkify 链接可点
let mdInstance = null

async function getMd() {
  if (mdInstance) return mdInstance
  const MarkdownIt = (await import('markdown-it')).default
  mdInstance = new MarkdownIt({
    html: false,
    breaks: true,
    linkify: true
  })
  return mdInstance
}

/** 同步兜底渲染（首帧实例未就绪时：至少去掉 ** 星号与首尾空白） */
function stripFallback(text) {
  return String(text == null ? '' : text)
    .replace(/\*\*(.+?)\*\*/g, '$1')
    .replace(/^#{1,6}\s+/gm, '')
}

export function useMarkdown() {
  const cache = new Map()

  /** 渲染为 HTML 片段（带简单缓存：同文本不重复 parse） */
  async function render(text) {
    const key = String(text || '')
    if (!key) return ''
    if (cache.has(key)) return cache.get(key)
    const md = await getMd()
    const html = md.render(key)
    if (cache.size > 200) cache.clear()
    cache.set(key, html)
    return html
  }

  return { render, stripFallback }
}

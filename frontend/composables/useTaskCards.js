// 任务卡：我的列表 / 创建 / PDF 下载（blob）
export function useTaskCards() {
  const api = useApi()
  const cards = ref([])

  async function loadMine() {
    try {
      const data = await api.get('/ag-ui/task-card/mine')
      cards.value = Array.isArray(data) ? data : []
    } catch (e) {
      cards.value = []
    }
  }

  async function create(payload) {
    return api.post('/ag-ui/task-card', payload)
  }

  async function downloadPdf(id) {
    const resp = await fetch('/ag-ui/task-card/' + id + '/pdf')
    if (resp.status === 401) { navigateTo('/login'); return }
    if (!resp.ok) throw new Error('HTTP ' + resp.status)
    const blob = await resp.blob()
    const url = URL.createObjectURL(blob)
    const a = document.createElement('a')
    a.href = url
    a.download = 'task-card-' + id + '.pdf'
    a.click()
    URL.revokeObjectURL(url)
  }

  return { cards, loadMine, create, downloadPdf }
}

// 统一 API 封装：同源 JSON 请求，401 自动跳登录；SSR 侧透传 Cookie 并拼绝对 URL
export function useApi() {
  async function request(path, { method = 'GET', body } = {}) {
    const opts = { method, headers: {} }
    if (import.meta.server) {
      const headers = useRequestHeaders(['cookie'])
      if (headers.cookie) opts.headers.cookie = headers.cookie
      path = useRequestURL().origin + path
    }
    if (body !== undefined) {
      opts.headers['Content-Type'] = 'application/json'
      opts.body = JSON.stringify(body)
    }
    const resp = await fetch(path, opts)
    if (resp.status === 401) {
      if (import.meta.client) navigateTo('/login')
      throw new Error('UNAUTHORIZED')
    }
    let data = null
    try { data = await resp.json() } catch (e) { /* 非 JSON 响应 */ }
    if (!resp.ok) {
      throw new Error((data && (data.message || data.error)) || ('HTTP ' + resp.status))
    }
    return data
  }

  return {
    get: (path) => request(path),
    post: (path, body) => request(path, { method: 'POST', body }),
    put: (path, body) => request(path, { method: 'PUT', body }),
    del: (path) => request(path, { method: 'DELETE' })
  }
}

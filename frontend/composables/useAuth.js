// 鉴权 composable：Cookie 会话，登录成功后立即拉取当前用户
export function useAuth() {
  const user = useState('auth.user', () => null)
  const api = useApi()

  async function login(username, password) {
    const data = await api.post('/api/auth/login', { username, password })
    if (data && data.success) {
      await me()
      return { ok: true }
    }
    return { ok: false, message: (data && data.message) || '登录失败' }
  }

  async function logout() {
    try { await api.post('/api/auth/logout') } catch (e) { /* 忽略 */ }
    user.value = null
    return navigateTo('/login')
  }

  // 未认证返回 null，由路由中间件处理跳转
  async function me() {
    try {
      const data = await api.get('/api/me')
      user.value = data
      return data
    } catch (e) {
      user.value = null
      return null
    }
  }

  const isAdmin = computed(() => !!(user.value && user.value.admin))

  return { user, isAdmin, login, logout, me }
}

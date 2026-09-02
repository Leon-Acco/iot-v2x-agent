// 全局鉴权中间件：除 /login 外均需有效会话，否则跳登录页
export default defineNuxtRouteMiddleware(async (to) => {
  if (to.path === '/login') return
  const { user, me } = useAuth()
  if (!user.value) {
    const u = await me()
    if (!u) return navigateTo('/login')
  }
})

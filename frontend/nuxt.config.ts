// Nuxt 4 配置：主题 + 后端代理
export default defineNuxtConfig({
  ssr: true,
  css: ['~/assets/css/theme.css'],
  components: [
    { path: '~/components', pathPrefix: false }
  ],
  app: {
    head: {
      htmlAttrs: { lang: 'zh-CN' },
      title: '车联网平台 Agent',
      meta: [
        { charset: 'utf-8' },
        { name: 'viewport', content: 'width=device-width, initial-scale=1.0' }
      ]
    }
  },
  nitro: {
    devProxy: {
      '/agui': { target: 'http://localhost:8080/agui', changeOrigin: true },
      '/ag-ui': { target: 'http://localhost:8080/ag-ui', changeOrigin: true },
      '/api': { target: 'http://localhost:8080/api', changeOrigin: true },
      // /admin 统一代理（含 capabilities/audit/memory/stats/a2a）
      '/admin': { target: 'http://localhost:8080/admin', changeOrigin: true }
    }
  },
  devtools: { enabled: false }
})

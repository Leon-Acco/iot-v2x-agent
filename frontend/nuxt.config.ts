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
    // 生产代理：routeRules 在 dev 与 nuxt build 生产构建中均生效（h3 直转，支持 SSE 流式）
    // proxy 目标必须携带相同路径前缀，否则剥前缀后后端 404
    routeRules: {
      '/agui': { proxy: 'http://localhost:8080/agui' },
      '/agui/**': { proxy: 'http://localhost:8080/agui/**' },
      '/ag-ui': { proxy: 'http://localhost:8080/ag-ui' },
      '/ag-ui/**': { proxy: 'http://localhost:8080/ag-ui/**' },
      '/api': { proxy: 'http://localhost:8080/api' },
      '/api/**': { proxy: 'http://localhost:8080/api/**' },
      // /admin 统一代理（含 capabilities/audit/memory/stats/a2a）
      '/admin': { proxy: 'http://localhost:8080/admin' },
      '/admin/**': { proxy: 'http://localhost:8080/admin/**' }
    }
  },
  devtools: { enabled: false }
})

// 高德 JSAPI 2.0 动态加载（client-only，防重复注入）
const AMAP_KEY = '0dccf5d823a10dd9698ea3a7ac319004'
let loadingPromise = null

export function useAMap() {
  function load() {
    if (import.meta.server) return Promise.resolve(null)
    if (window.AMap) return Promise.resolve(window.AMap)
    if (!loadingPromise) {
      loadingPromise = new Promise((resolve, reject) => {
        const s = document.createElement('script')
        s.src = 'https://webapi.amap.com/maps?v=2.0&key=' + AMAP_KEY
        s.onload = () => resolve(window.AMap)
        s.onerror = () => { loadingPromise = null; reject(new Error('AMap load failed')) }
        document.head.appendChild(s)
      })
    }
    return loadingPromise
  }
  return { load }
}

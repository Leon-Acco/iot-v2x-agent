<template>
  <!-- 布局壳：teal 渐变外框 + 毛玻璃内容卡（Office_Agent 复刻） -->
  <div class="frame">
    <div class="app-shell" :class="'theme-' + theme">
      <aside class="sidebar">
        <div class="sidebar-brand">
          <span class="sidebar-logo">V2X</span>
          <div class="brand-text">
            <h3>车联网Agent</h3>
            <p>设备运营 Agent</p>
          </div>
        </div>
        <SideNav :is-admin="isAdmin" />
        <div class="sidebar-footer">
          <UserCard :user="user" @logout="logout" />
        </div>
      </aside>
      <div class="main-content">
        <header class="page-header">
          <h1>{{ title }}</h1>
          <div class="header-right">
            <span class="clock">{{ clock }}</span>
            <span class="status-dot"></span>
            <span class="status-text">在线</span>
          </div>
        </header>
        <main class="page-body">
          <slot />
        </main>
      </div>
    </div>
  </div>
</template>

<script setup>
const props = defineProps({
  title: { type: String, default: '' },
  // 外框主题：blue 默认蓝渐变；green 设备孪生地图绿色渐变（DST 视觉稿）
  theme: { type: String, default: 'blue' }
})
const { user, isAdmin, logout } = useAuth()

const clock = ref('')
let timer = null
onMounted(() => {
  const tick = () => {
    clock.value = new Date().toLocaleString('zh-CN', { hour12: false })
  }
  tick()
  timer = setInterval(tick, 1000)
})
onBeforeUnmount(() => { if (timer) clearInterval(timer) })
</script>

<style scoped>
.frame { height: 100vh; background: var(--bg-page); }
.app-shell {
  position: relative;
  height: calc(100vh - 16px);
  margin: 8px;
  padding: 10px 10px 10px 0;
  background: linear-gradient(180deg, rgba(37, 99, 235, 0.86), rgba(30, 64, 175, 0.92));
  border: 1px solid rgba(255, 255, 255, 0.22);
  border-radius: 22px;
  overflow: hidden;
  box-shadow: 0 18px 48px rgba(30, 64, 175, 0.22), 0 4px 12px rgba(30, 64, 175, 0.10);
  display: flex;
}
/* 绿色主题：设备孪生地图页（DST 视觉稿色系） */
.app-shell.theme-green {
  background: linear-gradient(180deg, rgba(15, 138, 106, 0.88), rgba(11, 110, 85, 0.94));
  box-shadow: 0 18px 48px rgba(11, 110, 85, 0.22), 0 4px 12px rgba(11, 110, 85, 0.10);
}
/* 深色孪生主题：设备孪生地图驾驶仓 */
.frame:has(.app-shell.theme-twin) { background: #060C1C; }
.app-shell.theme-twin {
  background: linear-gradient(180deg, #0B1730 0%, #060C1C 100%);
  border-color: rgba(56, 189, 248, 0.18);
  box-shadow: 0 18px 48px rgba(4, 10, 26, 0.55), 0 4px 12px rgba(4, 10, 26, 0.35);
}
.app-shell.theme-twin .main-content {
  background: #081120;
  backdrop-filter: none;
  -webkit-backdrop-filter: none;
}
.app-shell.theme-twin .page-header h1 { color: #E6F1FF; }
.app-shell.theme-twin .header-right { color: #5A7194; }
.sidebar {
  width: var(--sider-width); min-width: var(--sider-width);
  height: 100%; flex-shrink: 0;
  display: flex; flex-direction: column;
  padding: 20px 12px 20px 26px;
}
.sidebar-brand {
  margin-bottom: 32px; padding: 0 8px;
  display: flex; align-items: center; gap: 10px;
}
.sidebar-logo {
  width: 40px; height: 40px; border-radius: 14px;
  background: rgba(255, 255, 255, 0.15);
  display: flex; align-items: center; justify-content: center;
  color: #fff; font-size: 13px; font-weight: 700;
  font-family: var(--font-num); letter-spacing: .5px;
}
.brand-text h3 { font-size: 16px; font-weight: 700; color: #fff; line-height: 1.2; margin: 0; }
.brand-text p { font-size: 11px; color: rgba(255, 255, 255, 0.6); margin: 2px 0 0; }
.sidebar-footer { margin-top: auto; }
.main-content {
  flex: 1; min-width: 0; height: 100%;
  overflow-y: auto;
  background: var(--glass);
  backdrop-filter: blur(20px) saturate(1.5);
  -webkit-backdrop-filter: blur(20px) saturate(1.5);
  border-radius: 20px;
  display: flex; flex-direction: column;
}
.page-header {
  display: flex; align-items: center; justify-content: space-between;
  padding: 24px 32px 12px;
  flex-shrink: 0;
}
.page-header h1 { font-size: 24px; font-weight: 500; color: var(--text-1); margin: 0; }
.header-right { display: flex; align-items: center; gap: 8px; color: var(--text-3); font-size: 12px; }
.clock { font-family: var(--font-num); }
.status-dot { width: 7px; height: 7px; border-radius: 50%; background: var(--success); }
.page-body { flex: 1; overflow: auto; padding: 8px 32px 24px; min-height: 0; }
</style>

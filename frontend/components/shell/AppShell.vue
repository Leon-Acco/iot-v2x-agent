<template>
  <!-- 布局壳：v3a「分组驾驶舱」——浅色分组左导航 + 浅色纸面工作区 -->
  <div class="app">
    <aside class="appnav" aria-label="应用导航">
      <div class="brandrow">
        <img class="mark" src="/images/dstLogo.png" alt="DST" />
        <b>车联网<i>Agent</i></b>
      </div>
      <SideNav :is-admin="isAdmin" />
      <button
        class="nav-fold"
        type="button"
        :aria-expanded="navFolded ? 'true' : 'false'"
        title="折叠导航"
        @click="toggleNav"
      >⟨</button>
      <UserCard :user="user" @logout="logout" />
    </aside>
    <main>
      <header class="topbar">
        <img class="m-mark" src="/images/dstLogo.png" alt="DST" />
        <h2>{{ title }}</h2>
        <slot name="topbar" />
        <div class="topbar-right">
          <span class="clock">{{ clock }}</span>
          <span class="status-dot"></span>
          <span class="status-text">在线</span>
          <span class="m-user">
            <span class="m-name">{{ user?.username || '' }}</span>
            <button class="m-logout" type="button" @click="logout">退出</button>
          </span>
        </div>
      </header>
      <div class="page">
        <slot />
      </div>
    </main>
  </div>
</template>

<script setup>
const props = defineProps({
  title: { type: String, default: '' },
  // 兼容旧调用（map 页传 theme="green"）；v3a 单一浅色皮肤，不再区分主题
  theme: { type: String, default: 'blue' }
})
const { user, isAdmin, logout } = useAuth()

// iOS 安全区：env(safe-area-inset-*) 需要 viewport-fit=cover（nuxt.config 不动，组件级补充）
useHead({
  meta: [{ name: 'viewport', content: 'width=device-width, initial-scale=1.0, viewport-fit=cover' }]
})

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

const navFolded = ref(false)
function toggleNav() {
  navFolded.value = !navFolded.value
  document.body.classList.toggle('folded-nav', navFolded.value)
}
</script>

<style scoped>
.app { display: flex; min-height: 100vh; }
.appnav {
  position: sticky; top: 0; height: 100vh; width: var(--sider-width); flex: 0 0 auto; z-index: 50;
  display: flex; flex-direction: column; padding: 18px 0 14px;
  background: var(--sider-bg);
  border-right: 1px solid var(--chrome-line);
}
.brandrow { display: flex; align-items: center; gap: 0; padding: 0 18px 16px; }
.brandrow .mark {
  height: 16px; width: auto; display: block;
  background: #FFFFFF; border-radius: 4px; padding: 2px 4px;
}
.brandrow b { font-size: 14px; font-weight: 900; color: var(--chrome-txt); white-space: nowrap; }
.brandrow b i { font-style: normal; color: var(--green); }
.nav-fold {
  display: none; margin: 10px auto 15px; width: 40px; height: 26px; border-radius: 8px;
  border: 1px solid var(--chrome-line); background: none; color: var(--chrome-t3);
  font: inherit; font-size: 12px; cursor: pointer;
}
.nav-fold:hover { color: var(--green); border-color: rgba(23, 160, 94, .5); }

main { flex: 1; min-width: 0; }
.topbar {
  display: flex; align-items: center; gap: 14px;
  height: var(--topbar-h); padding: 0 clamp(14px, 2.4vw, 30px);
  border-bottom: 1px solid var(--line);
}
.topbar h2 {
  margin: 0; font-size: 19px; font-weight: 900;
  min-width: 0; overflow: hidden; text-overflow: ellipsis; white-space: nowrap;
}
.topbar-right { margin-left: auto; display: flex; align-items: center; gap: 8px; }
.clock { font-family: var(--f-mono); font-size: 12px; color: var(--t2); }
.status-dot { width: 8px; height: 8px; border-radius: 50%; background: var(--green); flex: 0 0 auto; }
.status-text { font-size: 12px; color: var(--t2); }
/* 移动端顶栏 logo + 账号/退出（默认隐藏，≤760 展开；底部标签栏空间不足不放这里） */
.m-mark, .m-user { display: none; }
.page { padding: 12px clamp(14px, 2.4vw, 30px) var(--page-pad-b); }

@media (min-width: 761px) {
  .nav-fold { display: block; }
}
@media (max-width: 1020px) {
  .appnav { width: 64px; padding: 14px 0 12px; }
  .brandrow { justify-content: center; padding: 0 0 12px; }
  .brandrow b { display: none; }
}
@media (max-width: 760px) {
  .app { display: block; }
  .appnav {
    position: fixed; top: auto; bottom: 0; left: 0; right: 0; height: auto; width: 100%;
    flex-direction: row; align-items: center; gap: 2px;
    padding: 6px 8px calc(6px + env(safe-area-inset-bottom));
    border-right: 0; border-top: 1px solid var(--chrome-line);
    overflow-x: auto; justify-content: flex-start;
  }
  .brandrow, .nav-fold { display: none; }
  main { padding-bottom: 74px; }
  .page { padding: 10px 12px 28px; }
  .topbar { height: auto; min-height: var(--topbar-h); padding-top: 14px; padding-bottom: 10px; }
  .clock, .status-text { display: none; }
  .topbar h2 { font-size: 16px; }
  .m-mark { display: block; height: 16px; width: auto; flex: 0 0 auto; background: #FFFFFF; border-radius: 4px; padding: 2px 4px; }
  .m-user { display: inline-flex; align-items: center; gap: 8px; }
  .m-name {
    font-size: 12px; color: var(--t2); max-width: 72px;
    overflow: hidden; text-overflow: ellipsis; white-space: nowrap;
  }
  .m-logout {
    height: 28px; padding: 0 10px; border-radius: 999px;
    border: 1px solid var(--line-strong); background: none; color: var(--t3);
    font: inherit; font-size: 12px; cursor: pointer;
  }
  .m-logout:hover { color: var(--warn-ink); border-color: rgba(232, 122, 30, .4); }
}
</style>

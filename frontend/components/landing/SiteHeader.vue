<template>
  <!-- 导航栏：毛玻璃半透白，滚动吸顶 -->
  <header class="site-header" :class="{ scrolled }">
    <div class="header-inner">
      <a class="brand" href="#" aria-label="DST 车联网智能平台" @click.prevent>
        <span class="mark">DST</span>
        <span class="brand-txt">
          <b>车联网智能平台</b>
          <small>DST IOT INTELLIGENT PLATFORM</small>
        </span>
      </a>

      <nav class="nav-links" aria-label="主导航">
        <a href="#features" @click.prevent="scrollTo('features')">产品能力</a>
        <a href="#scenarios" @click.prevent="scrollTo('scenarios')">解决方案</a>
        <a href="#scenarios" @click.prevent="scrollTo('scenarios')">应用场景</a>
        <a href="#about" @click.prevent="scrollTo('about')">关于我们</a>
      </nav>

      <div class="actions">
        <button class="login-link" @click="$emit('enter')">登录</button>
        <button class="cta" @click="$emit('enter')">
          进入平台
          <svg viewBox="0 0 24 24" width="15" height="15" fill="none" stroke="currentColor" stroke-width="2.2" stroke-linecap="round" stroke-linejoin="round" aria-hidden="true"><path d="M5 12h14M13 6l6 6-6 6"/></svg>
        </button>
      </div>
    </div>
  </header>
</template>

<script setup>
defineEmits(['enter'])
const scrolled = ref(false)
function onScroll() {
  scrolled.value = window.scrollY > 12
}
onMounted(() => window.addEventListener('scroll', onScroll, { passive: true }))
onBeforeUnmount(() => window.removeEventListener('scroll', onScroll))

function scrollTo(id) {
  const el = document.getElementById(id)
  if (el) el.scrollIntoView({ behavior: 'smooth', block: 'start' })
}
</script>

<style scoped>
.site-header {
  position: sticky; top: 0; z-index: 50;
  background: rgba(255, 255, 255, 0.62);
  backdrop-filter: blur(16px) saturate(1.3);
  -webkit-backdrop-filter: blur(16px) saturate(1.3);
  border-bottom: 1px solid transparent;
  transition: background .25s ease, border-color .25s ease, box-shadow .25s ease;
}
.site-header.scrolled {
  background: rgba(255, 255, 255, 0.82);
  border-bottom-color: rgba(15, 138, 106, 0.10);
  box-shadow: 0 4px 20px rgba(15, 23, 42, 0.05);
}
.header-inner {
  max-width: 1440px; margin: 0 auto; height: 68px;
  display: flex; align-items: center; gap: 40px;
  padding: 0 40px;
}
.brand { display: flex; align-items: center; gap: 12px; flex-shrink: 0; }
.mark { font: 800 34px/1 "Segoe UI", Arial, sans-serif; letter-spacing: .02em; color: #0f8a6a; }
.brand-txt { padding-left: 13px; border-left: 1px solid rgba(15, 138, 106, 0.14); display: flex; flex-direction: column; gap: 3px; }
.brand-txt b { font-size: 17px; font-weight: 600; letter-spacing: .05em; color: #0F172A; }
.brand-txt small { font-size: 9px; letter-spacing: .2em; color: #94A3B8; }
.nav-links { display: flex; align-items: center; gap: 6px; margin-left: 8px; }
.nav-links a {
  padding: 8px 14px; border-radius: 999px;
  font-size: 14px; color: #475569;
  transition: color .15s ease, background .15s ease;
}
.nav-links a:hover { color: #0f8a6a; background: rgba(15, 138, 106, 0.07); }
.nav-links a:focus-visible { outline: 2px solid #0f8a6a; outline-offset: 2px; }
.actions { margin-left: auto; display: flex; align-items: center; gap: 18px; }
.login-link {
  border: none; background: transparent; font-size: 14px; font-weight: 500;
  color: #0F172A; cursor: pointer; padding: 8px 4px;
  transition: color .15s ease;
}
.login-link:hover { color: #0f8a6a; }
.cta {
  display: inline-flex; align-items: center; gap: 8px;
  height: 42px; padding: 0 20px; border: none; border-radius: 999px;
  background: linear-gradient(135deg, #3fb48d, #0f8a6a);
  color: #fff; font-size: 14px; font-weight: 600; cursor: pointer;
  box-shadow: 0 8px 20px rgba(15, 138, 106, 0.28);
  transition: transform .15s ease, box-shadow .2s ease;
}
.cta:hover { transform: translateY(-1px); box-shadow: 0 12px 26px rgba(15, 138, 106, 0.34); }
.cta:hover svg { transform: translateX(2px); }
.cta svg { transition: transform .15s ease; }
.cta:active { transform: translateY(0); }
.cta:focus-visible, .login-link:focus-visible { outline: 2px solid #0f8a6a; outline-offset: 2px; }
@media (max-width: 900px) {
  .nav-links { display: none; }
  .header-inner { padding: 0 20px; gap: 16px; }
  .mark { font-size: 28px; }
  .brand-txt small { display: none; }
}
</style>

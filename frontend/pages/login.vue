<template>
  <!-- 登录页：dst-iot-login 复刻 + 车辆闪点/扫描光带/能量环特效，演示账号一键登录 -->
  <main class="shell">
    <div class="crystals-bg"><ClientOnly><CrystalsVisual /></ClientOnly></div>
    <header class="top">
      <div class="brand">
        <div class="logo">DST</div>
        <div>
          <div class="brand-title">车联网Agent</div>
          <div class="brand-sub">DST IOT INTELLIGENT PLATFORM</div>
        </div>
      </div>
    </header>

    <section class="main">
      <div class="scene" aria-label="卡车数字孪生可视化">
        <div class="slogan">
          <h1><em>AI</em> 赋能车联<br>智慧掌控未来</h1>
          <p>用数据驱动智能出行，<br>让每一辆车更安全、更高效</p>
        </div>
      </div>

      <form class="login" @submit.prevent="onSubmit">
        <svg class="card-shield" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="1.7"><path d="M12 3 5 6v5c0 4.6 2.9 8 7 10 4.1-2 7-5.4 7-10V6l-7-3Z"/><path d="m9 12 2 2 4-4"/></svg>
        <h2>欢迎回来</h2>
        <p class="sub">登录 DST 车联网Agent</p>
        <p class="platform-tag">INTELLIGENT MOBILITY PLATFORM</p>
        <label class="field">
          <svg viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="1.7"><circle cx="12" cy="8" r="4"/><path d="M5 21v-2a7 7 0 0 1 14 0v2"/></svg>
          <input v-model.trim="username" aria-label="账号" placeholder="请输入账号" autocomplete="username" />
        </label>
        <label class="field">
          <svg viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="1.7"><rect x="5" y="10" width="14" height="11" rx="2"/><path d="M8 10V7a4 4 0 0 1 8 0v3"/></svg>
          <input v-model="password" :type="showPwd ? 'text' : 'password'" aria-label="密码" placeholder="请输入密码" autocomplete="current-password" />
          <button class="eye" type="button" :aria-label="showPwd ? '隐藏密码' : '显示密码'" @click="showPwd = !showPwd"><svg viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="1.7" style="width:18px;height:18px"><path d="M2 12s3.5-6.5 10-6.5S22 12 22 12s-3.5 6.5-10 6.5S2 12 22 12z"/><circle cx="12" cy="12" r="2.5"/></svg></button>
        </label>
        <div class="row">
          <label class="check"><input v-model="remember" type="checkbox" />记住我</label>
          <a class="link" href="#" @click.prevent>忘记密码？</a>
        </div>
        <p v-if="error" class="err">{{ error }}</p>
        <button class="submit" type="submit" :disabled="loading">{{ loading ? stageText : loginLabel }}<span class="btn-arrow" aria-hidden="true">→</span></button>
        <div class="or">演示账号已预填，直接登录即可</div>
        <div class="signup">admin / admin123 · op1 / op123456</div>
      </form>
    </section>
        <span class="corner-tag">DST IOT INTELLIGENT PLATFORM</span>
  </main>
</template>

<script setup>
definePageMeta({ layout: false })

const { login } = useAuth()
// 演示账号预填，一键登录
const username = ref('admin')
const password = ref('admin123')
const remember = ref(true)
const showPwd = ref(false)
const loading = ref(false)
const stageText = ref("")
const loginLabel = "登 录"
const error = ref('')

async function onSubmit() {
  if (!username.value || !password.value || loading.value) return
  loading.value = true
  error.value = ''
  stageText.value = "正在连接 AI 服务…"
  window.dispatchEvent(new CustomEvent("dst-login-pulse"))
  await new Promise((r) => setTimeout(r, 550))
  stageText.value = "正在验证身份…"
  await new Promise((r) => setTimeout(r, 550))
  try {
    const res = await login(username.value, password.value)
    if (res.ok) {
      stageText.value = "连接成功"
      await new Promise((r) => setTimeout(r, 380))
      navigateTo('/map')
    } else {
      error.value = res.message || '登录失败'
    }
  } catch (e) {
    error.value = '网络异常，请稍后重试'
  } finally {
    loading.value = false
  }
}
</script>

<style scoped>
.shell {
  --teal: #078779; --deep: #0a4544; --ink: #113e3f; --muted: #6a8382;
  --line: rgba(7, 135, 121, .16); --panel: rgba(255, 255, 255, .86);
  min-height: 100vh; position: relative; overflow: hidden;
  font-family: Inter, "Noto Sans SC", "Microsoft YaHei", system-ui, sans-serif;
  color: var(--ink);
  background:
    radial-gradient(circle at 43% 30%, rgba(255, 255, 255, .84), transparent 28%),
    radial-gradient(circle at 64% 44%, rgba(50, 184, 174, .15), transparent 35%),
    linear-gradient(135deg, #f6fbfa 0%, #e9f5f3 58%, #f7fbfb 100%);
}
.shell::after {
  content: ""; position: absolute; inset: 0; pointer-events: none; opacity: .32;
  background-image: linear-gradient(rgba(7, 135, 121, .035) 1px, transparent 1px), linear-gradient(90deg, rgba(7, 135, 121, .035) 1px, transparent 1px);
  background-size: 42px 42px;
  mask-image: linear-gradient(to bottom, black, transparent 86%);
}
.top { height: 112px; width: min(1420px, calc(100% - 88px)); margin: auto; display: flex; align-items: center; justify-content: space-between; position: relative; z-index: 3; }
.brand { display: flex; align-items: center; gap: 18px; }
.logo { font-weight: 1000; font-size: 61px; line-height: 1; letter-spacing: -5px; font-style: italic; background: linear-gradient(135deg, #087d70, #10a393); -webkit-background-clip: text; background-clip: text; color: transparent; }
.brand-title { font-size: 27px; font-weight: 800; letter-spacing: .02em; }
.brand-sub { font-size: 11px; letter-spacing: .08em; color: #547776; margin-top: 4px; }
.lang { border: 1px solid rgba(7, 135, 121, .14); background: rgba(255, 255, 255, .68); box-shadow: 0 5px 18px rgba(16, 83, 79, .06); border-radius: 25px; padding: 10px 15px; color: #17796f; display: flex; gap: 9px; align-items: center; min-height: 44px; cursor: pointer; }

.main { width: min(1420px, calc(100% - 88px)); min-height: 620px; margin: auto; display: grid; grid-template-columns: minmax(0, 1fr) 410px; gap: 64px; align-items: center; position: relative; z-index: 2; }
.scene { height: 620px; position: relative; }
.slogan { position: absolute; z-index: 3; top: 16px; left: 0; }
.slogan h1 { font-size: 30px; margin: 0 0 11px; letter-spacing: .025em; }
.slogan p { margin: 0; color: #6b8584; font-size: 15px; }
.world { position: absolute; width: 670px; height: 670px; left: 30%; top: -120px; border-radius: 50%; border: 1px solid rgba(51, 187, 175, .18); background: radial-gradient(circle at 46% 42%, rgba(255, 255, 255, .04), rgba(14, 147, 135, .04) 54%, transparent 55%); box-shadow: inset 0 0 70px rgba(16, 165, 151, .06); }
.world::before, .world::after { content: ""; position: absolute; border: 1px solid rgba(57, 188, 177, .16); border-radius: 50%; }
.world::before { inset: 12% 35%; }
.world::after { inset: 35% 9%; transform: rotate(18deg); }
.world i { position: absolute; inset: 7%; border-radius: 50%; border-top: 1px solid rgba(67, 200, 189, .26); transform: rotate(-14deg); }

.canvasbox { position: absolute; left: -4%; right: 2%; top: 126px; bottom: 0; transform-style: preserve-3d; will-change: transform; }
.canvasbox::after { content: ""; position: absolute; left: 2%; right: 0; bottom: 6%; height: 31%; border: 1px solid rgba(7, 135, 121, .16); border-radius: 50%; transform: perspective(620px) rotateX(62deg); box-shadow: inset 0 0 35px rgba(7, 135, 121, .05); }
.vehicle-image {
  width: 100%; height: 100%; object-fit: contain; display: block; position: relative; z-index: 1;
  /* 2x Lanczos baked sharpening, CSS keeps light contrast only */
  filter: contrast(1.05) saturate(1.02);
  animation: vehicleFloat 7s ease-in-out infinite; transform-origin: 50% 70%;
}
@keyframes vehicleFloat { 0%, 100% { transform: translateY(0) } 50% { transform: translateY(-12px) } }
#glitter { position: absolute; inset: 0; width: 100%; height: 100%; z-index: 2; mix-blend-mode: screen; opacity: .7; pointer-events: none; }
/* 扫描光带特效 */
.scan-band {
  position: absolute; top: -10%; bottom: -10%; left: 0; width: 34%; z-index: 3; pointer-events: none;
  background: linear-gradient(100deg, transparent 12%, rgba(140, 235, 220, .20) 46%, rgba(255, 255, 255, .5) 50%, rgba(140, 235, 220, .20) 54%, transparent 88%);
  mix-blend-mode: screen; animation: scan-sweep 5.2s ease-in-out infinite;
}
@keyframes scan-sweep {
  0%, 18% { transform: translateX(-70%) skewX(-12deg); opacity: 0; }
  32% { opacity: 1; }
  62%, 100% { transform: translateX(320%) skewX(-12deg); opacity: 0; }
}
/* 地面能量环脉冲 */
.pulse-ring {
  position: absolute; left: 24%; right: 24%; bottom: 10%; height: 16%; z-index: 0; pointer-events: none;
  border: 2px solid rgba(7, 135, 121, .35); border-radius: 50%;
  transform: perspective(620px) rotateX(62deg) scale(.7);
  animation: ring-pulse 3.2s ease-out infinite;
}
@keyframes ring-pulse {
  0% { transform: perspective(620px) rotateX(62deg) scale(.7); opacity: .8; }
  100% { transform: perspective(620px) rotateX(62deg) scale(1.35); opacity: 0; }
}
@media (prefers-reduced-motion: reduce) {
  .vehicle-image, .scan-band, .pulse-ring { animation: none; }
  .scan-band, .pulse-ring { display: none; }
}
/* ===== layout de-crowding overrides ===== */
.shell { display: flex; flex-direction: column; }
.top { height: 92px; flex: 0 0 auto; width: min(1440px, calc(100% - 96px)); }
.main { flex: 1 1 auto; min-height: 520px; width: min(1440px, calc(100% - 96px)); gap: 72px; grid-template-columns: minmax(0, 1.12fr) 430px; align-items: center; }
.scene { height: min(600px, 62vh); min-height: 420px; }
.world { left: 50%; top: 52%; width: min(620px, 92%); height: min(620px, 92%); transform: translate(-50%, -50%); }
.canvasbox { left: 0; right: 0; top: 100px; bottom: 0; max-width: 780px; margin: 0 auto; }
.login { padding: 40px 38px 30px; }
.field { height: 54px; margin-bottom: 20px; }
.eye { display: grid; place-items: center; }
.bottom { flex: 0 0 auto; margin-top: 8px; width: min(1440px, calc(100% - 96px)); }
.features { gap: 48px; padding: 24px 0 6px; }
.copyright { padding: 20px 0 16px; }
@media (max-width: 1050px) {
  .main { gap: 36px; grid-template-columns: minmax(0, 1fr) 400px; }
  .top, .main, .bottom { width: calc(100% - 56px); }
}
@media (max-width: 760px) {
  .shell { display: block; }
  .top, .main, .bottom { width: calc(100% - 32px); }
  .scene { height: 300px; min-height: 0; }
  .features { gap: 18px 10px; padding: 30px 0 4px; }
}

/* ===== blend & alignment fixes (round 2) ===== */
/* A. square image edge fade + multiply blend into page bg */
.vehicle-image {
  width: auto; height: 100%; max-width: 100%; margin: 0 auto;
  mix-blend-mode: multiply;
  -webkit-mask-image: radial-gradient(60% 60% at 50% 50%, #000 52%, rgba(0, 0, 0, 0) 80%);
  mask-image: radial-gradient(60% 60% at 50% 50%, #000 52%, rgba(0, 0, 0, 0) 80%);
}
/* B. enlarge visual, tighten slogan gap */
.scene { height: min(640px, 66vh); }
.canvasbox { top: 78px; max-width: 860px; }
.slogan h1 { font-size: 32px; margin-bottom: 8px; }
/* C. pulse ring above image so it reads as a full ellipse */
.pulse-ring { z-index: 3; }
/* D. features band: centered, hairline divider, aligned rhythm */
.features {
  max-width: 1150px; margin: 0 auto; gap: 64px;
  border-top: 1px solid rgba(7, 135, 121, .10); padding: 26px 0 6px;
}
.feature { justify-content: center; }
@media (max-width: 760px) {
  .features { max-width: none; gap: 18px 10px; }
  .canvasbox { top: 88px; }
}

/* ===== full-bleed crystals background (round 3) ===== */
.crystals-bg { position: absolute; inset: 0; z-index: 0; pointer-events: none; }
.top, .main, .bottom { width: calc(100% - 64px); max-width: none; }
.scene { height: auto; min-height: 0; }
@media (max-width: 760px) {
  .top, .main, .bottom { width: calc(100% - 32px); }
}

/* ===== dark theme (noomo reference look, round 4) ===== */
.shell { background: linear-gradient(135deg, #05060f 0%, #0a0e1c 60%, #070a16 100%); }
.shell::after { opacity: .10; }
.brand-title { color: #eef6f2; }
.brand-sub { color: rgba(220, 235, 230, .50); }
.slogan h1 { color: #f2f7f5; }
.slogan p { color: rgba(235, 245, 242, .62); }
.feature h3 { color: #e6f0ec; }
.feature p { color: rgba(220, 235, 230, .55); }
.fic { border-color: rgba(255, 255, 255, .14); background: rgba(255, 255, 255, .06); color: #7fd4bc; }
.features { border-top-color: rgba(255, 255, 255, .10); }
.copyright { color: rgba(200, 220, 214, .45); }
.copyright span { color: rgba(200, 220, 214, .25); }

/* ===== dark polish (round 5) ===== */
.fic { background: rgba(255, 255, 255, .10); border-color: rgba(255, 255, 255, .20); color: #8FD3BC; }
.feature h3 { color: #eef5f1; text-shadow: 0 1px 8px rgba(3, 8, 18, .6); }
.feature p { color: rgba(225, 240, 234, .68); text-shadow: 0 1px 6px rgba(3, 8, 18, .6); }
.copyright { text-shadow: 0 1px 6px rgba(3, 8, 18, .7); }

/* ===== feature icon glass chips (round 6) ===== */
.fic {
  background: rgba(8, 26, 34, .72); border: 1px solid rgba(95, 195, 165, .38);
  color: #5FC3A5;
  box-shadow: 0 0 18px rgba(95, 195, 165, .16), inset 0 0 12px rgba(95, 195, 165, .08);
  backdrop-filter: blur(8px); -webkit-backdrop-filter: blur(8px);
}
.fic svg { filter: drop-shadow(0 0 5px rgba(95, 195, 165, .5)); }

/* ===== dark glass login card + features strip (round 7) ===== */
.login {
  background: rgba(10, 22, 30, .55); border: 1px solid rgba(255, 255, 255, .12);
  box-shadow: 0 22px 65px rgba(0, 0, 0, .45);
}
.login h2 { color: #F0F6F3; }
.login > .sub { color: rgba(220, 235, 230, .60); }
.field { background: rgba(255, 255, 255, .07); border-color: rgba(255, 255, 255, .14); }
.field:focus-within { border-color: #2FA37C; box-shadow: 0 0 0 3px rgba(47, 163, 124, .18); }
.field input { color: #EAF4EF; }
.field input::placeholder { color: rgba(210, 228, 222, .45); }
.field svg { color: rgba(190, 215, 205, .65); }
.eye { color: rgba(190, 215, 205, .70); }
.row { color: rgba(210, 228, 222, .65); }
.check input { accent-color: #2FA37C; }
.link { color: #5FC3A5; }
.or { color: rgba(200, 220, 214, .40); }
.or::before, .or::after { background: rgba(255, 255, 255, .12); }
.signup { color: rgba(200, 220, 214, .50); }
.features {
  background: rgba(6, 16, 24, .45); border: 1px solid rgba(255, 255, 255, .08);
  border-radius: 18px; padding: 18px 24px;
  backdrop-filter: blur(10px); -webkit-backdrop-filter: blur(10px);
}
.fic { background: rgba(95, 195, 165, .12); border-color: rgba(95, 195, 165, .45); }
.feature h3 { font-size: 15px; }
.feature p { color: rgba(225, 240, 234, .75); }

/* ===== login card polish (round 8) ===== */
.login {
  border-radius: 22px; padding: 38px 36px 28px;
  box-shadow: 0 24px 70px rgba(0, 0, 0, .50), inset 0 1px 0 rgba(255, 255, 255, .08);
}
.field { border-radius: 14px; }
.submit:hover { box-shadow: 0 14px 34px rgba(7, 135, 121, .45); filter: brightness(1.08); }

/* ===== login card de-intrusion (round 9) ===== */
.login {
  background: linear-gradient(160deg, rgba(12, 26, 34, .48), rgba(8, 18, 26, .36));
  border-color: rgba(255, 255, 255, .10); position: relative;
}
.login::before {
  content: ""; position: absolute; left: 24px; right: 24px; top: 0; height: 2px;
  border-radius: 2px; background: linear-gradient(90deg, transparent, rgba(95, 195, 165, .55), transparent);
}

/* ===== login card elevation + brand unify (round 10) ===== */
.login {
  border: 1px solid rgba(255, 255, 255, .16);
  box-shadow:
    0 8px 24px rgba(0, 0, 0, .35),
    0 32px 80px rgba(0, 0, 0, .55),
    0 0 0 1px rgba(15, 138, 106, .10),
    inset 0 1px 0 rgba(255, 255, 255, .10);
}

/* ===== back to light white-green theme (round 11) ===== */
.shell { background: linear-gradient(135deg, #f6fbfa 0%, #e9f5f3 58%, #f7fbfb 100%); }
.shell::after { opacity: .32; }
.brand-title { color: #113e3f; }
.brand-sub { color: #547776; }
.slogan h1 { color: #113e3f; text-shadow: none; }
.slogan p { color: #6b8584; }
.login {
  background: rgba(255, 255, 255, .72); border: 1px solid rgba(255, 255, 255, .9);
  box-shadow: 0 22px 65px rgba(20, 91, 87, .18), inset 0 1px 0 rgba(255, 255, 255, .9);
}
.login::before { background: linear-gradient(90deg, transparent, rgba(15, 138, 106, .5), transparent); }
.login h2 { color: #113e3f; }
.login > .sub { color: #6c8584; }
.field { background: rgba(255, 255, 255, .72); border-color: rgba(17, 82, 79, .14); }
.field:focus-within { border-color: #18a292; box-shadow: 0 0 0 3px rgba(24, 162, 146, .10); }
.field input { color: #153f3e; }
.field input::placeholder { color: #879997; }
.field svg { color: #718a89; }
.eye { color: #718a89; }
.row { color: #587776; }
.link { color: #087f73; }
.or { color: #91a19f; }
.or::before, .or::after { background: #dce9e7; }
.signup { color: #708684; }

/* ===== AI data-space polish (round 13) ===== */
.slogan h1 { font-size: 34px; line-height: 1.35; letter-spacing: .04em; }
.slogan h1 em { font-style: normal; color: #079B8D; }
.slogan p { font-size: 14px; line-height: 1.9; color: #6F8C89; }
.field svg { color: #8BA4A1; }
.eye { color: #8BA4A1; }
.platform-tag { margin: 10px 0 0; font-size: 9.5px; letter-spacing: 3px; color: #8BA4A1; opacity: .85; }

/* ===== back to dark theme (round 14) ===== */
.shell { background: linear-gradient(135deg, #05060f 0%, #0a0e1c 60%, #070a16 100%); }
.shell::after { opacity: .10; }
.brand-title { color: #eef6f2; }
.brand-sub { color: rgba(220, 235, 230, .50); }
.slogan h1 { color: #f2f7f5; }
.slogan h1 em { color: #5FC3A5; }
.slogan p { color: rgba(235, 245, 242, .62); }
.login {
  background: linear-gradient(160deg, rgba(12, 26, 34, .48), rgba(8, 18, 26, .36));
  border: 1px solid rgba(255, 255, 255, .16);
  box-shadow: 0 8px 24px rgba(0, 0, 0, .35), 0 32px 80px rgba(0, 0, 0, .55),
    0 0 0 1px rgba(15, 138, 106, .10), inset 0 1px 0 rgba(255, 255, 255, .10);
}
.login::before { background: linear-gradient(90deg, transparent, rgba(95, 195, 165, .55), transparent); }
.login h2 { color: #F0F6F3; }
.login > .sub { color: rgba(220, 235, 230, .60); }
.field { background: rgba(255, 255, 255, .07); border-color: rgba(255, 255, 255, .14); }
.field:focus-within { border-color: #2FA37C; box-shadow: 0 0 0 3px rgba(47, 163, 124, .18); }
.field input { color: #EAF4EF; }
.field input::placeholder { color: rgba(210, 228, 222, .45); }
.field svg { color: rgba(190, 215, 205, .65); }
.eye { color: rgba(190, 215, 205, .70); }
.row { color: rgba(210, 228, 222, .65); }
.link { color: #5FC3A5; }
.or { color: rgba(200, 220, 214, .40); }
.or::before, .or::after { background: rgba(255, 255, 255, .12); }
.signup { color: rgba(200, 220, 214, .50); }
.platform-tag { color: rgba(190, 215, 205, .55); }

/* ===== light mint ice theme (round 15) ===== */
.shell { background: linear-gradient(135deg, #f4faf7 0%, #e4f2ec 58%, #f7fbf9 100%); }
.shell::after { opacity: .25; }
.brand-title { color: #113e3f; }
.brand-sub { color: #547776; }
.slogan { top: auto; bottom: 7%; }
.slogan h1 { color: #113e3f; text-shadow: none; }
.slogan h1 em { color: #079B8D; }
.slogan p { color: #6F8C89; }
.login {
  background: rgba(255, 255, 255, .78); border: 1px solid rgba(255, 255, 255, .9);
  box-shadow: 0 22px 60px rgba(20, 91, 87, .16), inset 0 1px 0 rgba(255, 255, 255, .9);
}
.login::before { background: linear-gradient(90deg, transparent, rgba(15, 138, 106, .5), transparent); }
.login h2 { color: #113e3f; }
.login > .sub { color: #6c8584; }
.field { background: rgba(255, 255, 255, .72); border-color: rgba(17, 82, 79, .14); }
.field:focus-within { border-color: #18a292; box-shadow: 0 0 0 3px rgba(24, 162, 146, .10); }
.field input { color: #153f3e; }
.field input::placeholder { color: #879997; }
.field svg { color: #8BA4A1; }
.eye { color: #8BA4A1; }
.row { color: #587776; }
.link { color: #087f73; }
.or { color: #91a19f; }
.or::before, .or::after { background: #dce9e7; }
.signup { color: #708684; }
.platform-tag { color: #8BA4A1; }

/* ===== card inset + tag pill (round 16) ===== */
.login { margin-right: 6vw; }

/* ===== mockup fidelity (round 17) ===== */
.slogan { top: 30%; bottom: auto; }
.slogan h1 { position: relative; padding-bottom: 14px; }
.slogan h1::after { content: ""; position: absolute; left: 0; bottom: 0; width: 56px; height: 3px; border-radius: 2px; background: #079B8D; }
.card-shield { position: absolute; top: 22px; right: 24px; width: 22px; height: 22px; color: #0F8A6A; opacity: .8; }
.login { position: relative; width: 400px; max-width: 400px; margin-right: 5vw; }
.submit { position: relative; }
.btn-arrow { position: absolute; right: 22px; top: 50%; transform: translateY(-50%); font-size: 16px; }
.corner-tag {
  position: absolute; left: 34px; bottom: 18px; z-index: 4;
  font-size: 9.5px; letter-spacing: 2.5px; color: #8BA4A1; opacity: .75;
}

/* ===== dark theme with full backdrop (round 18) ===== */
.shell { background: linear-gradient(135deg, #04100c 0%, #071a15 58%, #051310 100%); }
.shell::after { opacity: .10; }
.brand-title { color: #eef6f2; }
.brand-sub { color: rgba(220, 235, 230, .50); }
.slogan h1 { color: #f2f7f5; }
.slogan h1 em { color: #5FC3A5; }
.slogan p { color: rgba(235, 245, 242, .62); }
.login {
  background: linear-gradient(160deg, rgba(12, 26, 34, .52), rgba(8, 18, 26, .40));
  border: 1px solid rgba(255, 255, 255, .16);
  box-shadow: 0 8px 24px rgba(0, 0, 0, .35), 0 32px 80px rgba(0, 0, 0, .55),
    0 0 0 1px rgba(15, 138, 106, .12), inset 0 1px 0 rgba(255, 255, 255, .10);
}
.login::before { background: linear-gradient(90deg, transparent, rgba(95, 195, 165, .55), transparent); }
.login h2 { color: #F0F6F3; }
.login > .sub { color: rgba(220, 235, 230, .60); }
.field { background: rgba(255, 255, 255, .07); border-color: rgba(255, 255, 255, .14); }
.field:focus-within { border-color: #2FA37C; box-shadow: 0 0 0 3px rgba(47, 163, 124, .18); }
.field input { color: #EAF4EF; }
.field input::placeholder { color: rgba(210, 228, 222, .45); }
.field svg { color: rgba(190, 215, 205, .65); }
.eye { color: rgba(190, 215, 205, .70); }
.row { color: rgba(210, 228, 222, .65); }
.link { color: #5FC3A5; }
.or { color: rgba(200, 220, 214, .40); }
.or::before, .or::after { background: rgba(255, 255, 255, .12); }
.signup { color: rgba(200, 220, 214, .50); }
.platform-tag { color: rgba(190, 215, 205, .55); }
.corner-tag { color: rgba(160, 195, 185, .55); }
.card-shield { color: #5FC3A5; }

/* ===== pure black base (round 19) ===== */
.shell { background: linear-gradient(135deg, #030405 0%, #060809 58%, #040506 100%); }

</style>

<style scoped>
.login { border: 1px solid rgba(255, 255, 255, .9); border-radius: 26px; background: var(--panel); box-shadow: 0 22px 65px rgba(20, 91, 87, .14); backdrop-filter: blur(22px); padding: 34px 34px 27px; }
.login h2 { font-size: 28px; margin: 0 0 8px; }
.login > .sub { color: #6c8584; margin: 0 0 28px; }
.field { height: 52px; border: 1px solid rgba(17, 82, 79, .14); background: rgba(255, 255, 255, .72); border-radius: 13px; margin-bottom: 18px; display: flex; align-items: center; padding: 0 16px; transition: .2s; }
.field:focus-within { border-color: #18a292; box-shadow: 0 0 0 3px rgba(24, 162, 146, .1); }
.field svg { width: 19px; color: #718a89; flex: 0 0 auto; }
.field input { width: 100%; height: 100%; border: 0; outline: 0; background: transparent; padding: 0 12px; color: #153f3e; }
.field input::placeholder { color: #879997; }
.eye { cursor: pointer; border: 0; background: none; color: #718a89; padding: 6px; }
.row { display: flex; align-items: center; justify-content: space-between; font-size: 13px; color: #587776; margin: 2px 0 26px; }
.check { display: flex; align-items: center; gap: 8px; }
.check input { width: 16px; height: 16px; accent-color: var(--teal); }
.link { color: #087f73; cursor: pointer; text-decoration: none; }
.err { color: #DC2626; font-size: 12px; margin: -12px 0 12px; }
.submit { height: 52px; border: 0; border-radius: 26px; width: 100%; background: linear-gradient(100deg, #07897b, #079b89); color: #fff; font-weight: 800; letter-spacing: .08em; box-shadow: 0 12px 24px rgba(7, 135, 121, .22); cursor: pointer; transition: .2s; }
.submit:hover { transform: translateY(-2px); box-shadow: 0 16px 30px rgba(7, 135, 121, .28); }
.submit:disabled { opacity: .6; transform: none; }
.or { display: flex; align-items: center; gap: 12px; color: #91a19f; font-size: 12px; margin: 27px 0 20px; }
.or::before, .or::after { content: ""; height: 1px; background: #dce9e7; flex: 1; }
.signup { text-align: center; font-size: 12.5px; color: #708684; margin-top: 6px; }
.bottom { width: min(1420px, calc(100% - 88px)); margin: 0 auto; position: relative; z-index: 3; }
.features { display: grid; grid-template-columns: repeat(4, 1fr); gap: 38px; }
.feature { display: flex; align-items: center; gap: 16px; justify-content: center; }
.fic { width: 54px; height: 54px; border-radius: 50%; display: grid; place-items: center; border: 1px solid rgba(7, 135, 121, .13); background: rgba(255, 255, 255, .35); color: #287b74; }
.fic svg { width: 25px; }
.feature h3 { font-size: 14px; margin: 0 0 6px; }
.feature p { font-size: 12px; color: #7a908e; margin: 0; }
.copyright { text-align: center; color: #40918a; font-size: 12px; padding: 27px 0 18px; }
.copyright span { display: inline-block; margin: 0 13px; color: #c4d9d6; }

@media (max-width: 1050px) {
  .main { grid-template-columns: 1fr 390px; gap: 25px; }
  .world { left: 15%; }
  .slogan h1 { font-size: 25px; }
  .features { gap: 10px; }
  .feature { justify-content: flex-start; }
  .top, .main, .bottom { width: calc(100% - 48px); }
}
@media (max-width: 760px) {
  .shell { overflow: auto; }
  .top { height: 88px; width: calc(100% - 32px); }
  .logo { font-size: 40px; letter-spacing: -3px; }
  .brand { gap: 11px; }
  .brand-title { font-size: 17px; }
  .brand-sub { font-size: 8px; }
  .lang { font-size: 12px; padding: 7px 10px; }
  .main { display: flex; flex-direction: column; width: calc(100% - 32px); gap: 22px; min-height: 0; }
  .scene { height: 310px; width: 100%; }
  .slogan h1 { font-size: 23px; }
  .slogan p { font-size: 13px; }
  .world { width: 360px; height: 360px; left: 26%; top: -35px; }
  .canvasbox { top: 91px; left: -22%; right: -23%; bottom: -30px; }
  .login { width: 100%; padding: 26px 22px; border-radius: 21px; }
  .features { grid-template-columns: repeat(2, 1fr); gap: 20px 10px; margin-top: 38px; }
  .feature { gap: 10px; }
  .fic { width: 43px; height: 43px; flex: 0 0 auto; }
}
</style>

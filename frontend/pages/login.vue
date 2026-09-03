<template>
  <!-- login page: dark holo theme, 高斯泼溅全息台 truck (SplatTruck) + glass form -->
  <main class="login-page">
    <div class="bg"></div>
    <div class="scanline"></div>
    <ClientOnly><SplatTruck :shift-x="-0.16" :shift-y="0.02" :size="1.3" :depth="0" :drift="0" :auto-spin="false" /></ClientOnly>

    <header class="top">
      <div class="brand">
        <div class="logo">DST</div>
        <div>
          <div class="brand-title">{{ t.brandTitle }}</div>
          <div class="brand-sub">DST IOT INTELLIGENT PLATFORM</div>
        </div>
      </div>
      <div class="top-tag mono">GAUSSIAN SPLAT &middot; HOLO DECK</div>
    </header>

    <section class="form-side">
      <form class="login" @submit.prevent="onSubmit">
        <svg class="card-shield" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="1.7"><path d="M12 3 5 6v5c0 4.6 2.9 8 7 10 4.1-2 7-5.4 7-10V6l-7-3Z"/><path d="m9 12 2 2 4-4"/></svg>
        <h2>{{ t.welcome }}</h2>
        <p class="sub">{{ t.sub }}</p>
        <p class="platform-tag">INTELLIGENT MOBILITY PLATFORM</p>
        <label class="field">
          <svg viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="1.7"><circle cx="12" cy="8" r="4"/><path d="M5 21v-2a7 7 0 0 1 14 0v2"/></svg>
          <input v-model.trim="username" aria-label="account" :placeholder="t.account" autocomplete="username" />
        </label>
        <label class="field">
          <svg viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="1.7"><rect x="5" y="10" width="14" height="11" rx="2"/><path d="M8 10V7a4 4 0 0 1 8 0v3"/></svg>
          <input v-model="password" :type="showPwd ? 'text' : 'password'" aria-label="password" :placeholder="t.password" autocomplete="current-password" />
          <button class="eye" type="button" :aria-label="showPwd ? 'hide' : 'show'" @click="showPwd = !showPwd"><svg viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="1.7" style="width:18px;height:18px"><path d="M2 12s3.5-6.5 10-6.5S22 12 22 12s-3.5 6.5-10 6.5S2 12 22 12z"/><circle cx="12" cy="12" r="2.5"/></svg></button>
        </label>
        <div class="row">
          <label class="check"><input v-model="remember" type="checkbox" />{{ t.remember }}</label>
          <a class="link" href="#" @click.prevent>{{ t.forgot }}</a>
        </div>
        <p v-if="error" class="err">{{ error }}</p>
        <button class="submit" type="submit" :disabled="loading">{{ loading ? stageText : t.login }}<span class="btn-arrow" aria-hidden="true">&#8594;</span></button>
        <div class="or">{{ t.orNote }}</div>
        <div class="signup">admin / admin123 &middot; op1 / op123456</div>
      </form>
    </section>

    <div class="hero-copy">
      <h1>让每一辆车，<br><em>更智能、更安全</em></h1>
      <p>{{ t.heroDesc }}</p>
    </div>

    <div class="hint mono">
      <span><i></i>{{ t.hint1 }}</span>
      <span><i></i>{{ t.hint2 }}</span>
    </div>

    <span class="corner-tag mono">DST IOT INTELLIGENT PLATFORM</span>
  </main>
</template>

<script setup>
definePageMeta({ layout: false })

const { login } = useAuth()
const username = ref('admin')
const password = ref('admin123')
const remember = ref(true)
const showPwd = ref(false)
const loading = ref(false)
const stageText = ref('')
const error = ref('')

const t = {
  brandTitle: '车联网Agent',
  welcome: '欢迎回来',
  sub: '登录 DST 车联网Agent',
  account: '请输入账号',
  password: '请输入密码',
  remember: '记住我',
  forgot: '忘记密码？',
  login: '登 录',
  orNote: '演示账号已预填，直接登录即可',
  heroDesc: '融合 AI、物联网与大数据能力，实时掌握车辆状态，智能分析运营风险，全面提升车队管理效率。',
  hint1: '移动鼠标 — 扰动粒子场',
  hint2: '点击画面 — 冲击波散开'
}

async function onSubmit() {
  if (!username.value || !password.value || loading.value) return
  loading.value = true
  error.value = ''
  stageText.value = '正在验证身份…'
  try {
    const res = await login(username.value, password.value)
    if (res.ok) {
      // 不等待，直接进入过渡页（粒子笑脸）
      navigateTo('/transition')
    } else {
      error.value = res.message || '账号或密码错误'
    }
  } catch (e) {
    error.value = '网络异常，请稍后重试'
  } finally {
    loading.value = false
  }
}
</script>

<style scoped>
.login-page {
  position: fixed; inset: 0; overflow: hidden;
  background: #021316; color: #d3fffe;
}

/* deep background glow + grid */
.bg {
  position: absolute; inset: 0; z-index: 0;
  background:
    radial-gradient(130% 100% at 42% 42%, rgba(6, 51, 58, 0.9) 0%, rgba(3, 32, 37, 0.6) 46%, rgba(2, 19, 22, 0) 100%),
    radial-gradient(720px 420px at 82% 30%, rgba(64, 232, 220, 0.07), transparent 65%);
}
.bg::after {
  content: ''; position: absolute; inset: 0;
  background-image:
    linear-gradient(rgba(64, 232, 220, 0.045) 1px, transparent 1px),
    linear-gradient(90deg, rgba(64, 232, 220, 0.045) 1px, transparent 1px);
  background-size: 56px 56px;
  mask-image: radial-gradient(90% 90% at 50% 50%, #000 30%, transparent 100%);
  -webkit-mask-image: radial-gradient(90% 90% at 50% 50%, #000 30%, transparent 100%);
}

/* HUD scanline */
.scanline {
  position: absolute; left: 0; right: 0; top: -12%;
  height: 12%; z-index: 2; pointer-events: none;
  background: linear-gradient(to bottom,
    transparent 0%, rgba(64, 232, 220, 0.05) 70%,
    rgba(64, 232, 220, 0.14) 96%, rgba(211, 255, 254, 0.30) 100%);
  mix-blend-mode: screen;
  animation: sweep 7s cubic-bezier(.45, 0, .55, 1) infinite;
}
@keyframes sweep {
  0% { transform: translateY(0); }
  55% { transform: translateY(1020%); }
  100% { transform: translateY(1020%); }
}

.mono {
  font-family: ui-monospace, "SF Mono", "JetBrains Mono", Menlo, Consolas, monospace;
  font-feature-settings: "tnum";
}

/* header */
.top {
  position: absolute; top: 0; left: 0; right: 0; z-index: 6;
  display: flex; align-items: center; justify-content: space-between;
  padding: 22px 36px;
}
.brand { display: flex; align-items: center; gap: 12px; }
.logo {
  font-size: 26px; font-weight: 800; font-style: italic; letter-spacing: 1px;
  background: linear-gradient(135deg, #40e8dc, #adefed);
  -webkit-background-clip: text; background-clip: text; color: transparent;
}
.brand-title { font-size: 15px; font-weight: 700; color: #d3fffe; }
.brand-sub { font-size: 10px; letter-spacing: 2px; color: rgba(127, 201, 198, 0.55); }
.top-tag {
  font-size: 10px; letter-spacing: 0.18em; color: #7fc9c6;
  padding: 7px 12px; border: 1px solid rgba(64, 232, 220, 0.22); border-radius: 4px;
  background: rgba(3, 30, 34, 0.5);
}

/* glass form */
.form-side {
  position: absolute; right: 4.5%; top: 50%; transform: translateY(-50%); z-index: 5;
  width: min(400px, 92vw);
  display: flex; align-items: center; justify-content: center;
}
.login {
  width: 100%; padding: 38px 34px 30px;
  background: rgba(3, 30, 34, 0.62);
  backdrop-filter: blur(16px) saturate(1.3);
  -webkit-backdrop-filter: blur(16px) saturate(1.3);
  border: 1px solid rgba(64, 232, 220, 0.18); border-radius: 18px;
  box-shadow: 0 0 0 1px rgba(2, 19, 22, 0.6), 0 18px 50px rgba(0, 0, 0, 0.45),
              inset 0 1px 0 rgba(211, 255, 254, 0.08);
  text-align: center;
}
.card-shield { width: 40px; height: 40px; color: #40e8dc; margin-bottom: 10px; }
.login h2 { margin: 0; font-size: 24px; font-weight: 800; color: #d3fffe; }
.login .sub { margin: 8px 0 2px; font-size: 13px; color: rgba(127, 201, 198, 0.75); }
.platform-tag { margin: 0 0 22px; font-size: 10px; letter-spacing: 2.5px; color: rgba(127, 201, 198, 0.45); }

.field {
  display: flex; align-items: center; gap: 10px;
  margin-bottom: 14px; padding: 0 14px; height: 46px;
  background: rgba(2, 19, 22, 0.55);
  border: 1px solid rgba(64, 232, 220, 0.16); border-radius: 10px;
  transition: border-color .15s ease, box-shadow .15s ease;
}
.field:focus-within { border-color: #40e8dc; box-shadow: 0 0 0 3px rgba(64, 232, 220, 0.14); }
.field svg { width: 19px; height: 19px; color: rgba(127, 201, 198, 0.6); flex-shrink: 0; }
.field input { flex: 1; border: none; outline: none; background: transparent; font-size: 14px; color: #d3fffe; }
.field input::placeholder { color: rgba(127, 201, 198, 0.4); }
.eye { border: none; background: none; cursor: pointer; color: rgba(127, 201, 198, 0.6); padding: 2px; }

.row {
  display: flex; align-items: center; justify-content: space-between;
  margin: 4px 2px 16px; font-size: 12px; color: rgba(127, 201, 198, 0.75);
}
.check { display: flex; align-items: center; gap: 6px; cursor: pointer; }
.check input { accent-color: #40e8dc; }
.link { color: #40e8dc; text-decoration: none; }

.err {
  margin: 0 0 12px; padding: 8px 12px; font-size: 12px; text-align: left;
  color: #ff9a8a; background: rgba(255, 106, 90, 0.1); border-radius: 8px;
}

.submit {
  width: 100%; height: 46px; border: none; border-radius: 10px; cursor: pointer;
  display: flex; align-items: center; justify-content: center; gap: 8px;
  background: linear-gradient(135deg, #2a827e, #40e8dc); color: #021316;
  font-size: 15px; font-weight: 700; letter-spacing: 2px;
  box-shadow: 0 0 18px rgba(64, 232, 220, 0.3);
  transition: transform .15s ease, box-shadow .15s ease, background .3s ease;
}
.submit:hover:not(:disabled) { background: linear-gradient(135deg, #34a29c, #adefed); box-shadow: 0 0 28px rgba(64, 232, 220, 0.55); transform: translateY(-1px); }
.submit:disabled { opacity: 0.75; cursor: default; }
.btn-arrow { font-size: 17px; letter-spacing: 0; }

.or { margin: 18px 0 6px; font-size: 12px; color: rgba(127, 201, 198, 0.5); }
.signup { font-size: 11px; color: rgba(127, 201, 198, 0.55); }

/* hero copy */
.hero-copy { position: absolute; left: 6%; bottom: 7%; z-index: 4; pointer-events: none; }
.hero-copy h1 {
  margin: 0; font-size: 40px; line-height: 1.28; font-weight: 800; color: #d3fffe;
  text-shadow: 0 2px 24px rgba(2, 19, 22, 0.9);
}
.hero-copy h1 em { font-style: normal; color: #40e8dc; text-shadow: 0 0 24px rgba(64, 232, 220, 0.45); }
.hero-copy p { margin: 12px 0 0; font-size: 14px; color: rgba(127, 201, 198, 0.8); max-width: 420px; }

/* interaction hint */
.hint {
  position: absolute; left: 50%; bottom: 20px; transform: translateX(-50%); z-index: 6;
  font-size: 11px; letter-spacing: 0.12em; color: #7fc9c6; pointer-events: none;
  display: flex; gap: 1.8em; white-space: nowrap;
}
.hint i {
  font-style: normal; display: inline-block;
  width: 6px; height: 6px; border-radius: 50%;
  background: #40e8dc; margin-right: 8px; vertical-align: 1px;
  box-shadow: 0 0 6px #40e8dc;
}

.corner-tag {
  position: absolute; right: 24px; bottom: 16px; z-index: 6;
  font-size: 10px; letter-spacing: 3px; color: rgba(127, 201, 198, 0.45);
}

@media (max-width: 900px) {
  .form-side { right: 50%; transform: translate(50%, -50%); }
  .hero-copy { display: none; }
  .hint { display: none; }
  .top-tag { display: none; }
}
</style>

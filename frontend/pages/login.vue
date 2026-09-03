<template>
  <!-- login page: unified scene, hero image multiply-blended over water surface -->
  <main class="login-page">
    <div class="sky"></div>
    <div class="beams"><i></i><i></i><i></i></div>

    <div class="hero-wrap">
      <img class="hero-img" src="/images/img_1.png" :alt="t.brandTitle" />
      <img class="hero-reflect" src="/images/img_1.png" aria-hidden="true" />
    </div>

    <ClientOnly><WaterRipple /></ClientOnly>

    <header class="top">
      <div class="brand">
        <div class="logo">DST</div>
        <div>
          <div class="brand-title">{{ t.brandTitle }}</div>
          <div class="brand-sub">DST IOT INTELLIGENT PLATFORM</div>
        </div>
      </div>
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
      <h1>AI <em>{{ t.heroEm }}</em><br>{{ t.heroLine2 }}</h1>
      <p>{{ t.heroDesc }}</p>
    </div>

    <span class="corner-tag">DST IOT INTELLIGENT PLATFORM</span>
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
  orNote: '演示账号已预填，直接登录即可'
}

function rippleStyle(n) {
  const lefts = [16, 30, 48, 64, 80]
  return {
    left: lefts[(n - 1) % 5] + '%',
    animationDelay: ((n - 1) * 1.3) + 's',
    animationDuration: (5.2 + (n % 3)) + 's'
  }
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
  background: #C6DEE3; color: #12403E;
}

/* ===== sky ===== */
.sky {
  position: absolute; inset: 0; z-index: 0;
  background:
    radial-gradient(900px 480px at 30% 18%, rgba(255, 255, 255, 0.55), transparent 60%),
    radial-gradient(700px 420px at 78% 30%, rgba(43, 191, 175, 0.10), transparent 65%),
    linear-gradient(180deg, #E6F2F5 0%, #CFE4E9 46%, #B7D6DD 72%, #A5CBD4 100%);
}
.beams { position: absolute; inset: 0; z-index: 1; overflow: hidden; pointer-events: none; }
.beams i {
  position: absolute; top: -30%; width: 220px; height: 160%;
  background: linear-gradient(180deg, rgba(255, 255, 255, 0.35), rgba(255, 255, 255, 0));
  transform: rotate(18deg); filter: blur(28px); opacity: .5;
  animation: beam-drift 14s ease-in-out infinite;
}
.beams i:nth-child(1) { left: 12%; }
.beams i:nth-child(2) { left: 44%; animation-delay: -5s; opacity: .35; }
.beams i:nth-child(3) { left: 74%; animation-delay: -9s; opacity: .28; }
@keyframes beam-drift {
  0%, 100% { transform: rotate(18deg) translateX(0); }
  50% { transform: rotate(18deg) translateX(46px); }
}

/* ===== hero ===== */
.hero-wrap {
  position: absolute; left: 4%; bottom: 14%; z-index: 3;
  width: min(54vw, 780px); pointer-events: none;
  animation: hero-float 7s ease-in-out infinite;
}
.hero-img {
  width: 100%; display: block;
  mix-blend-mode: multiply;
  -webkit-mask-image: radial-gradient(130% 130% at 50% 46%, #000 58%, transparent 90%);
  mask-image: radial-gradient(130% 130% at 50% 46%, #000 58%, transparent 90%);
}
.hero-reflect {
  position: absolute; top: 100%; left: 0; width: 100%;
  transform: scaleY(-1); mix-blend-mode: multiply;
  opacity: .16; filter: blur(3px);
  -webkit-mask-image: linear-gradient(to top, #000 0%, transparent 55%);
  mask-image: linear-gradient(to top, #000 0%, transparent 55%);
}
@keyframes hero-float {
  0%, 100% { transform: translateY(0); }
  50% { transform: translateY(-10px); }
}


/* ===== header ===== */
.top {
  position: absolute; top: 0; left: 0; right: 0; z-index: 6;
  display: flex; align-items: center; justify-content: space-between;
  padding: 22px 36px;
}
.brand { display: flex; align-items: center; gap: 12px; }
.logo {
  font-size: 26px; font-weight: 800; font-style: italic; letter-spacing: 1px;
  background: linear-gradient(135deg, #0E8A8A, #2BBFAF);
  -webkit-background-clip: text; background-clip: text; color: transparent;
}
.brand-title { font-size: 15px; font-weight: 700; color: #12403E; }
.brand-sub { font-size: 10px; letter-spacing: 2px; color: rgba(18, 64, 62, 0.45); }

/* ===== floating form ===== */
.form-side {
  position: absolute; right: 4.5%; top: 50%; transform: translateY(-50%); z-index: 5;
  width: min(400px, 92vw);
  display: flex; align-items: center; justify-content: center;
}
.login {
  width: 100%; padding: 38px 34px 30px;
  background: rgba(255, 255, 255, 0.55); backdrop-filter: blur(20px);
  border: 1px solid rgba(255, 255, 255, 0.65); border-radius: 22px;
  box-shadow: 0 18px 50px rgba(18, 64, 62, 0.16);
  text-align: center;
}
.card-shield { width: 40px; height: 40px; color: #0E8A8A; margin-bottom: 10px; }
.login h2 { margin: 0; font-size: 24px; font-weight: 800; color: #12403E; }
.login .sub { margin: 8px 0 2px; font-size: 13px; color: rgba(18, 64, 62, 0.55); }
.platform-tag { margin: 0 0 22px; font-size: 10px; letter-spacing: 2.5px; color: rgba(18, 64, 62, 0.35); }

.field {
  display: flex; align-items: center; gap: 10px;
  margin-bottom: 14px; padding: 0 14px; height: 46px;
  background: rgba(255, 255, 255, 0.78);
  border: 1px solid rgba(14, 138, 138, 0.16); border-radius: 12px;
  transition: border-color .15s ease, box-shadow .15s ease;
}
.field:focus-within { border-color: #0E8A8A; box-shadow: 0 0 0 3px rgba(14, 138, 138, 0.12); }
.field svg { width: 19px; height: 19px; color: rgba(18, 64, 62, 0.45); flex-shrink: 0; }
.field input { flex: 1; border: none; outline: none; background: transparent; font-size: 14px; color: #12403E; }
.field input::placeholder { color: rgba(18, 64, 62, 0.35); }
.eye { border: none; background: none; cursor: pointer; color: rgba(18, 64, 62, 0.45); padding: 2px; }

.row {
  display: flex; align-items: center; justify-content: space-between;
  margin: 4px 2px 16px; font-size: 12px; color: rgba(18, 64, 62, 0.6);
}
.check { display: flex; align-items: center; gap: 6px; cursor: pointer; }
.check input { accent-color: #0E8A8A; }
.link { color: #0E8A8A; text-decoration: none; }

.err {
  margin: 0 0 12px; padding: 8px 12px; font-size: 12px; text-align: left;
  color: #B3402E; background: rgba(179, 64, 46, 0.08); border-radius: 8px;
}

.submit {
  width: 100%; height: 46px; border: none; border-radius: 12px; cursor: pointer;
  display: flex; align-items: center; justify-content: center; gap: 8px;
  background: linear-gradient(135deg, #0E8A8A, #2BBFAF); color: #fff;
  font-size: 15px; font-weight: 700; letter-spacing: 2px;
  box-shadow: 0 10px 24px rgba(14, 138, 138, 0.35);
  transition: transform .15s ease, box-shadow .15s ease;
}
.submit:hover:not(:disabled) { transform: translateY(-1px); box-shadow: 0 14px 30px rgba(14, 138, 138, 0.45); }
.submit:disabled { opacity: 0.75; cursor: default; }
.btn-arrow { font-size: 17px; letter-spacing: 0; }

.or { margin: 18px 0 6px; font-size: 12px; color: rgba(18, 64, 62, 0.4); }
.signup { font-size: 11px; color: rgba(18, 64, 62, 0.45); }

.hero-copy { position: absolute; left: 6%; bottom: 6%; z-index: 4; pointer-events: none; }
.hero-copy h1 {
  margin: 0; font-size: 42px; line-height: 1.25; font-weight: 800; color: #12403E;
  text-shadow: 0 2px 18px rgba(255, 255, 255, 0.85);
}
.hero-copy h1 em { font-style: normal; color: #0E8A8A; }
.hero-copy p { margin: 12px 0 0; font-size: 15px; color: rgba(18, 64, 62, 0.62); max-width: 420px; }

.corner-tag {
  position: absolute; left: 24px; bottom: 16px; z-index: 6;
  font-size: 10px; letter-spacing: 3px; color: rgba(18, 64, 62, 0.45);
}

@media (max-width: 900px) {
  .hero-wrap { width: 88vw; left: 6%; bottom: 30%; opacity: .55; }
  .form-side { right: 50%; transform: translate(50%, -50%); }
}
</style>
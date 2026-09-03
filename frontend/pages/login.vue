<template>
  <!-- 登录页：H3「孪生线框车（细节版）」——WebGL 舞台（TwinStage）+ 左文案 / 右登录卡双栏 -->
  <main class="login-page">
    <ClientOnly><TwinStage /></ClientOnly>

    <div class="content">
      <section class="hero" aria-label="品牌区">
        <div class="brandrow">
          <svg class="mark" viewBox="0 0 44 44" fill="none" aria-hidden="true">
            <circle cx="22" cy="22" r="19" stroke="#17A05E" stroke-width="1.6" opacity=".9" />
            <ellipse cx="22" cy="22" rx="20" ry="8" stroke="#17A05E" stroke-width="1" opacity=".55" transform="rotate(-24 22 22)" />
            <circle cx="22" cy="22" r="4.5" fill="#17A05E" />
            <circle cx="38.5" cy="15.5" r="2.2" fill="#E87A1E" />
          </svg>
          <b>DST <i>车联网Agent</i></b>
          <small>TWIN INSPECTOR</small>
        </div>
        <h1>每一辆车，都有<br />一个<em>数字孪生体</em></h1>
        <p class="sub">旋转眼前的线框车 —— <b>地面之下，是它秒级同步的孪生镜像</b>。<br />登录车联网Agent，接管车队的另一端。</p>
      </section>

      <section class="panel">
        <form class="login" novalidate @submit.prevent="onSubmit">
          <div class="panel-head">
            <div class="badge">DST</div>
            <div>
              <h2>登录 · 车联网Agent</h2>
              <p>车联网Agent · 车载数字孪生平台</p>
            </div>
          </div>
          <label class="field">
            <svg viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="1.7" aria-hidden="true"><circle cx="12" cy="8" r="4" /><path d="M5 21v-2a7 7 0 0 1 14 0v2" /></svg>
            <input v-model.trim="username" aria-label="账号" :placeholder="t.account" autocomplete="username" />
          </label>
          <label class="field">
            <svg viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="1.7" aria-hidden="true"><rect x="5" y="10" width="14" height="11" rx="2" /><path d="M8 10V7a4 4 0 0 1 8 0v3" /></svg>
            <input v-model="password" :type="showPwd ? 'text' : 'password'" aria-label="密码" :placeholder="t.password" autocomplete="current-password" />
            <button class="eye" type="button" :aria-label="showPwd ? '隐藏密码' : '显示密码'" @click="showPwd = !showPwd">
              <svg viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="1.7" aria-hidden="true"><path d="M2 12s3.5-6.5 10-6.5S22 12 22 12s-3.5 6.5-10 6.5S2 12 2 12z" /><circle cx="12" cy="12" r="2.5" /></svg>
            </button>
          </label>
          <div class="row">
            <label class="check"><input v-model="remember" type="checkbox" />{{ t.remember }}</label>
            <a class="link" href="#" @click.prevent>{{ t.forgot }}</a>
          </div>
          <p v-if="error" class="err">{{ error }}</p>
          <button class="submit" type="submit" :disabled="loading">{{ loading ? stageText : t.login }}</button>
          <div class="demo">演示账号已预填<code>admin / admin123</code></div>
        </form>
        <p class="panel-foot">© 2026 DST 车联网 · 车载数字孪生平台</p>
      </section>
    </div>

    <span class="hint">拖拽旋转 · 滚轮缩放</span>
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
  account: '请输入账号',
  password: '请输入密码',
  remember: '记住我',
  forgot: '忘记密码？',
  login: '登 录'
}

async function onSubmit() {
  if (!username.value || !password.value || loading.value) return
  loading.value = true
  error.value = ''
  stageText.value = '正在同步孪生体…'
  try {
    const res = await login(username.value, password.value)
    if (res.ok) {
      // 与 H3 动效一致：短暂展示同步完成后进入过渡页；保持 loading 防重复提交
      stageText.value = '同步完成 ✓'
      setTimeout(() => navigateTo('/transition'), 500)
      return
    }
    error.value = res.message || '账号或密码错误'
  } catch (e) {
    error.value = '网络异常，请稍后重试'
  }
  loading.value = false
}
</script>

<style scoped>
/* 样式照抄 login-preview-h3-twin3d-detail.html（浅色皮肤，令牌取自全局 theme.css） */
.login-page { position: relative; }
.content {
  position: relative; z-index: 2; min-height: 100vh; max-width: 1240px; margin: auto;
  display: grid; grid-template-columns: 1fr 410px; align-items: center;
  gap: clamp(28px, 4vw, 72px); padding: clamp(24px, 4vw, 56px);
}

.brandrow { display: flex; align-items: center; gap: 12px; margin-bottom: 18px; }
.mark { width: 40px; height: 40px; }
.brandrow b { font-size: 19px; font-weight: 900; }
.brandrow b i { font-style: normal; color: var(--green); }
.brandrow small { font-family: var(--f-mono); font-size: 10px; letter-spacing: 3px; color: var(--t3); margin-left: 10px; }
h1 { margin: 0 0 12px; font-size: clamp(30px, 3.8vw, 46px); font-weight: 900; line-height: 1.24; letter-spacing: .01em; }
h1 em { font-style: normal; color: var(--green); }
.sub { margin: 0; font-size: 14px; color: var(--t2); line-height: 1.9; max-width: 460px; }
.sub b { color: var(--ink); font-weight: 700; }

.panel {
  background: rgba(255, 255, 255, .92); border: 1px solid var(--line); border-radius: 20px;
  padding: 38px 36px 26px; box-shadow: 0 24px 60px rgba(13, 64, 38, .12);
}
.panel-head { display: flex; align-items: center; gap: 15px; margin-bottom: 24px; }
.badge {
  width: 54px; height: 54px; border-radius: 50%; flex: 0 0 auto;
  border: 2px solid var(--green); display: grid; place-items: center;
  font: italic 900 16px Arial, sans-serif; color: var(--green); background: rgba(23, 160, 94, .08);
}
.panel-head h2 { margin: 0 0 3px; font-size: 20px; font-weight: 800; }
.panel-head p { margin: 0; font-size: 12.5px; color: var(--t2); }
.field {
  display: flex; align-items: center; gap: 10px; height: 50px;
  background: #F5F8F6; border: 1px solid var(--line); border-radius: 12px;
  padding: 0 15px; margin-bottom: 16px; transition: border-color .15s, box-shadow .15s;
}
.field:focus-within { border-color: var(--green); box-shadow: 0 0 0 3px rgba(23, 160, 94, .15); }
.field svg { width: 18px; height: 18px; color: var(--t3); flex: 0 0 auto; }
.field input { flex: 1; min-width: 0; border: 0; outline: 0; background: none; font: inherit; color: var(--ink); height: 100%; }
.field input::placeholder { color: var(--t3); }
.eye { border: none; background: none; cursor: pointer; color: var(--t3); padding: 2px; }
.eye svg { width: 18px; height: 18px; display: block; }
.row { display: flex; justify-content: space-between; align-items: center; font-size: 12.5px; color: var(--t2); margin: 4px 0 22px; }
.check { display: flex; align-items: center; gap: 7px; cursor: pointer; }
.check input { width: 15px; height: 15px; accent-color: var(--green); }
.link { color: var(--green); text-decoration: none; }
.link:hover { color: var(--orange); }
.err {
  margin: -10px 0 14px; padding: 8px 12px; font-size: 12px; text-align: left;
  color: var(--red); background: rgba(214, 69, 69, .1); border-radius: 8px;
}
.submit {
  width: 100%; height: 50px; border: 0; border-radius: 12px; background: var(--green);
  color: #fff; font: inherit; font-weight: 900; font-size: 15px; letter-spacing: .12em; cursor: pointer;
  box-shadow: 0 12px 28px rgba(23, 160, 94, .28); transition: .2s;
}
.submit:hover:not(:disabled) { background: var(--green-deep); transform: translateY(-1px); }
.submit:disabled { opacity: .65; cursor: wait; }
.demo {
  margin-top: 16px; font-size: 12px; color: var(--t3);
  display: flex; flex-wrap: wrap; justify-content: center; align-items: center; gap: 6px;
}
.demo code {
  font-family: var(--f-mono); background: #F0F5F2; border: 1px solid var(--line);
  padding: 3px 10px; border-radius: 999px; color: var(--t2); white-space: nowrap;
}
.panel-foot { margin-top: 22px; text-align: center; font-size: 11.5px; color: var(--t3); }

.hint {
  position: fixed; left: 16px; bottom: 14px; z-index: 4;
  font-family: var(--f-mono); font-size: 10.5px; letter-spacing: 1px; color: var(--t3);
  border: 1px solid var(--line); border-radius: 999px; padding: 5px 11px;
  background: rgba(255, 255, 255, .7); pointer-events: none;
}

@media (max-width: 1020px) {
  .content { grid-template-columns: 1fr; align-items: start; }
  .panel { width: min(440px, 100%); justify-self: center; }
  .content::before {
    content: ""; position: fixed; inset: 0; z-index: 1; pointer-events: none;
    background: radial-gradient(90% 70% at 50% 42%, rgba(247, 250, 248, 0), transparent 78%);
  }
}
@media (max-width: 560px) {
  .panel { padding: 28px 22px 20px; }
}
</style>

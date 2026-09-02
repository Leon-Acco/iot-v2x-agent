<template>
  <!-- Hero 左栏文案：标题/副标题/能力标签/双 CTA/信任信息 -->
  <div class="hero-copy">
    <h1>让每一辆车，<br>更智能、更安全</h1>
    <p class="sub">融合 AI、物联网与大数据能力，实时掌握车辆状态，智能分析运营风险，全面提升车队管理效率。</p>
    <div class="tags" aria-label="核心能力">
      <span>实时感知</span><i></i>
      <span>智能分析</span><i></i>
      <span>风险预警</span><i></i>
      <span>高效运营</span>
    </div>
    <div class="cta-row">
      <button class="cta-primary" @click="$emit('enter')">
        立即体验
        <svg viewBox="0 0 24 24" width="16" height="16" fill="none" stroke="currentColor" stroke-width="2.2" stroke-linecap="round" stroke-linejoin="round" aria-hidden="true"><path d="M5 12h14M13 6l6 6-6 6"/></svg>
      </button>
      <a class="cta-secondary" href="#scenarios" @click.prevent="scrollToScenarios">
        查看解决方案
      </a>
    </div>
    <div class="trust" aria-label="信任信息">
      <span><i class="ok"></i>7×24 小时实时监控</span>
      <span><i class="ok"></i>毫秒级风险预警</span>
    </div>
  </div>
</template>

<script setup>
defineEmits(['enter'])
function scrollToScenarios() {
  const el = document.getElementById('scenarios')
  if (el) el.scrollIntoView({ behavior: 'smooth', block: 'start' })
}
</script>

<style scoped>
.hero-copy { max-width: 600px; }
h1 {
  margin: 0; font-size: clamp(40px, 4.4vw, 64px); line-height: 1.12;
  font-weight: 800; letter-spacing: -0.025em; color: #0F172A;
}
.sub {
  margin: 22px 0 0; max-width: 520px;
  font-size: 17px; line-height: 1.75; color: #475569;
}
.tags {
  display: flex; align-items: center; flex-wrap: wrap; gap: 10px;
  margin-top: 22px; font-size: 13.5px; font-weight: 500; color: #2563EB;
}
.tags i { width: 4px; height: 4px; border-radius: 50%; background: #38BDF8; }
.cta-row { display: flex; align-items: center; gap: 16px; margin-top: 34px; }
.cta-primary {
  display: inline-flex; align-items: center; gap: 10px;
  height: 52px; padding: 0 28px; border: none; border-radius: 999px;
  background: linear-gradient(135deg, #38BDF8, #2563EB 60%, #1D4ED8);
  color: #fff; font-size: 15px; font-weight: 600; letter-spacing: .02em; cursor: pointer;
  box-shadow: 0 14px 30px rgba(37, 99, 235, 0.32);
  transition: transform .15s ease, box-shadow .2s ease;
}
.cta-primary:hover { transform: translateY(-2px); box-shadow: 0 18px 38px rgba(37, 99, 235, 0.38); }
.cta-primary:hover svg { transform: translateX(3px); }
.cta-primary svg { transition: transform .15s ease; }
.cta-primary:active { transform: translateY(0); }
.cta-primary:focus-visible { outline: 2px solid #1D4ED8; outline-offset: 3px; }
.cta-secondary {
  display: inline-flex; align-items: center;
  height: 52px; padding: 0 26px; border-radius: 999px;
  border: 1px solid rgba(37, 99, 235, 0.28);
  background: rgba(255, 255, 255, 0.6);
  font-size: 15px; font-weight: 500; color: #2563EB;
  transition: background .15s ease, border-color .15s ease;
}
.cta-secondary:hover { background: rgba(37, 99, 235, 0.07); border-color: #2563EB; }
.cta-secondary:focus-visible { outline: 2px solid #2563EB; outline-offset: 3px; }
.trust { display: flex; gap: 24px; margin-top: 34px; }
.trust span { display: inline-flex; align-items: center; gap: 7px; font-size: 12.5px; color: #475569; }
.trust .ok { width: 7px; height: 7px; border-radius: 50%; background: #059669; display: inline-block; }
@media (max-width: 900px) {
  .cta-row { flex-wrap: wrap; }
}
</style>

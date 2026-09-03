<template>
  <!-- login transition: particle smiley + staged texts, auto forward -->
  <main class="transition-page">
    <canvas ref="cv" class="face-cv"></canvas>
    <div class="stage-text">{{ stageText }}<span class="dots"><i></i><i></i><i></i></span></div>
    <div class="sub-text">DST IOT INTELLIGENT PLATFORM</div>
  </main>
</template>

<script setup>
definePageMeta({ layout: false })

const cv = ref(null)
const stageText = ref('正在连接 AI 服务')

function loadScript(src) {
  return new Promise((resolve, reject) => {
    const s = document.createElement('script')
    s.src = src
    s.onload = resolve
    s.onerror = reject
    document.body.appendChild(s)
  })
}

onMounted(async () => {
  try {
    await loadScript('/vendor/three.min.js')
    await loadScript('/vendor/login-particles.js')
    if (window.createParticleFace) {
      window.createParticleFace(cv.value, { interactive: false })
    }
  } catch (e) { /* particles optional */ }
  await new Promise(r => setTimeout(r, 900))
  stageText.value = '正在验证身份'
  await new Promise(r => setTimeout(r, 700))
  stageText.value = '连接成功'
  await new Promise(r => setTimeout(r, 450))
  navigateTo('/map')
})
</script>

<style scoped>
.transition-page {
  position: fixed; inset: 0; overflow: hidden;
  display: flex; flex-direction: column; align-items: center; justify-content: center;
  background: linear-gradient(150deg, #DCEBEF 0%, #C6DEE3 58%, #BCD8DE 100%);
}
.face-cv { width: min(52vmin, 420px); height: min(52vmin, 420px); }
.stage-text {
  margin-top: 28px; font-size: 17px; font-weight: 600; color: #12403E;
  display: flex; align-items: center; gap: 4px;
}
.dots { display: inline-flex; gap: 3px; margin-left: 4px; }
.dots i {
  width: 5px; height: 5px; border-radius: 50%; background: #0E8A8A;
  animation: dot-bounce 1.2s ease-in-out infinite;
}
.dots i:nth-child(2) { animation-delay: .18s; }
.dots i:nth-child(3) { animation-delay: .36s; }
.sub-text { margin-top: 12px; font-size: 10px; letter-spacing: 3px; color: rgba(18, 64, 62, 0.4); }

@keyframes dot-bounce {
  0%, 60%, 100% { transform: translateY(0); opacity: .5; }
  30% { transform: translateY(-5px); opacity: 1; }
}
</style>

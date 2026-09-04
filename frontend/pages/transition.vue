<template>
  <!-- login transition: mascot gif + staged texts, auto forward -->
  <main class="transition-page">
    <img class="face-img" src="/images/jixiangwu.gif" alt="DST 吉祥物" />
    <div class="stage-text">{{ stageText }}<span class="dots"><i></i><i></i><i></i></span></div>
    <div class="sub-text">DST IOT INTELLIGENT PLATFORM</div>
  </main>
</template>

<script setup>
definePageMeta({ layout: false })

const stageText = ref('正在连接 AI 服务')

onMounted(async () => {
  await new Promise(r => setTimeout(r, 800))
  stageText.value = '正在验证身份'
  await new Promise(r => setTimeout(r, 500))
  stageText.value = '连接成功'
  await new Promise(r => setTimeout(r, 300))
  navigateTo('/chat')
})
</script>

<style scoped>
.transition-page {
  position: fixed; inset: 0; overflow: hidden;
  display: flex; flex-direction: column; align-items: center; justify-content: center;
  background: #f5f4f4; /* 与 jixiangwu.gif 底色一致（全帧主色 47.4%） */
}
.face-img {
  width: min(52vmin, 420px); height: auto;
  max-height: 56vh; object-fit: contain;
}
.stage-text {
  margin-top: 28px; font-size: 17px; font-weight: 600; color: var(--ink);
  display: flex; align-items: center; gap: 4px;
}
.dots { display: inline-flex; gap: 3px; margin-left: 4px; }
.dots i {
  width: 5px; height: 5px; border-radius: 50%; background: var(--green);
  animation: dot-bounce 1.2s ease-in-out infinite;
}
.dots i:nth-child(2) { animation-delay: .18s; }
.dots i:nth-child(3) { animation-delay: .36s; }
.sub-text { margin-top: 12px; font-size: 10px; letter-spacing: 3px; color: var(--t3); }

@keyframes dot-bounce {
  0%, 60%, 100% { transform: translateY(0); opacity: .5; }
  30% { transform: translateY(-5px); opacity: 1; }
}
</style>

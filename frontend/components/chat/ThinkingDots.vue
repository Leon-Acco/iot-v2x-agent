<template>
  <!-- 三点弹跳：思考中动画 -->
  <span class="thinking-dots"><i></i><i></i><i></i></span>
</template>

<style scoped>
.thinking-dots { display: inline-flex; gap: 4px; align-items: center; height: 14px; }
.thinking-dots i {
  width: 5px; height: 5px; border-radius: 50%; background: var(--primary);
  animation: bounce 1.2s infinite ease-in-out;
}
.thinking-dots i:nth-child(2) { animation-delay: .15s; }
.thinking-dots i:nth-child(3) { animation-delay: .3s; }
@keyframes bounce {
  0%, 60%, 100% { transform: translateY(0); opacity: .5; }
  30% { transform: translateY(-4px); opacity: 1; }
}
</style>

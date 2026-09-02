<template>
  <!-- 悬浮数据卡：半透明玻璃质感 -->
  <div class="metric-card" :style="{ '--delay': delay + 's' }" role="status">
    <div class="label">{{ label }}</div>
    <div class="value">{{ value }}<span v-if="unit" class="unit">{{ unit }}</span></div>
    <div v-if="sub" class="sub" :class="subTone">{{ sub }}</div>
  </div>
</template>

<script setup>
defineProps({
  label: { type: String, required: true },
  value: { type: [String, Number], required: true },
  unit: { type: String, default: '' },
  sub: { type: String, default: '' },
  subTone: { type: String, default: '' },
  delay: { type: Number, default: 0 }
})
</script>

<style scoped>
.metric-card {
  background: rgba(255, 255, 255, 0.72);
  border: 1px solid rgba(255, 255, 255, 0.9);
  border-radius: 14px;
  padding: 14px 18px;
  backdrop-filter: blur(12px);
  -webkit-backdrop-filter: blur(12px);
  box-shadow: 0 8px 24px rgba(15, 23, 42, 0.08);
  animation: float-y 5s ease-in-out infinite;
  animation-delay: var(--delay);
}
.label { font-size: 12px; color: #475569; letter-spacing: .02em; }
.value {
  font-family: var(--font-num, "Inter", sans-serif);
  font-size: 24px; font-weight: 700; color: #0F172A;
  margin-top: 3px; line-height: 1.2;
}
.unit { font-size: 13px; font-weight: 500; color: #94A3B8; margin-left: 4px; }
.sub { font-size: 11px; margin-top: 4px; color: #059669; display: flex; align-items: center; gap: 5px; }
.sub::before { content: ''; width: 6px; height: 6px; border-radius: 50%; background: currentColor; }
.sub.warn { color: #DC2626; }
.sub.faint { color: #94A3B8; }
@keyframes float-y {
  0%, 100% { transform: translateY(0); }
  50% { transform: translateY(-8px); }
}
@media (prefers-reduced-motion: reduce) { .metric-card { animation: none; } }
</style>

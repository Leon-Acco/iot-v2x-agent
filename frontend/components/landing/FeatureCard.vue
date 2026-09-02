<template>
  <!-- 功能卡：线性图标 + 标题 + 一句描述，悬浮上浮高亮边框 -->
  <article class="feature-card">
    <span class="ic" aria-hidden="true" v-html="iconSvg"></span>
    <h3>{{ title }}</h3>
    <p>{{ desc }}</p>
  </article>
</template>

<script setup>
const props = defineProps({
  title: { type: String, required: true },
  desc: { type: String, required: true },
  icon: { type: String, default: 'monitor' }
})
const ICONS = {
  monitor: '<svg viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="1.7" stroke-linecap="round" stroke-linejoin="round"><rect x="3" y="4" width="18" height="13" rx="2"/><path d="M8 21h8m-4-4v4"/><circle cx="12" cy="10.5" r="2.4"/></svg>',
  chart: '<svg viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="1.7" stroke-linecap="round" stroke-linejoin="round"><path d="M4 20V10M10 20V4M16 20v-7M22 20H2"/></svg>',
  ai: '<svg viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="1.7" stroke-linecap="round" stroke-linejoin="round"><path d="M12 3l1.8 4.6L18 9l-4.2 1.4L12 15l-1.8-4.6L6 9l4.2-1.4z"/><path d="M19 15l.9 2.1L22 18l-2.1.9L19 21l-.9-2.1L16 18l2.1-.9z"/></svg>',
  shield: '<svg viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="1.7" stroke-linecap="round" stroke-linejoin="round"><path d="M12 3l7 3v5c0 4.5-2.9 7.7-7 9-4.1-1.3-7-4.5-7-9V6z"/><path d="M9.5 12l1.8 1.8 3.4-3.6"/></svg>'
}
const iconSvg = computed(() => ICONS[props.icon] || ICONS.monitor)
</script>

<style scoped>
.feature-card {
  background: #fff;
  border: 1px solid rgba(37, 99, 235, 0.12);
  border-radius: 16px;
  padding: 26px 24px;
  transition: transform .2s ease, box-shadow .25s ease, border-color .2s ease, background .25s ease;
}
.feature-card:hover {
  transform: translateY(-4px);
  border-color: rgba(37, 99, 235, 0.35);
  background: linear-gradient(180deg, #FFFFFF 0%, #F5F9FF 100%);
  box-shadow: 0 16px 36px rgba(37, 99, 235, 0.12);
}
.ic {
  width: 48px; height: 48px; border-radius: 12px;
  background: rgba(37, 99, 235, 0.08); color: #2563EB;
  display: grid; place-items: center;
}
.ic :deep(svg) { width: 22px; height: 22px; }
h3 { font-size: 16px; font-weight: 600; color: #0F172A; margin: 16px 0 8px; }
p { font-size: 13.5px; line-height: 1.7; color: #475569; margin: 0; }
</style>

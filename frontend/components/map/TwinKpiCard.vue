<template>
  <!-- KPI 卡：大数字 + 英文小标 + meta 行，可点击跳转 -->
  <div class="kpi-card" :class="['accent-' + accent, { clickable: to || clickable }]" @click="go">
    <div class="kpi-head">
      <span class="kpi-title">{{ title }}</span>
      <span class="kpi-en">{{ en }}</span>
    </div>
    <div class="kpi-num"><b>{{ value }}</b><i>{{ unit }}</i></div>
    <div class="kpi-meta"><slot>{{ meta }}</slot></div>
    <span class="kpi-glow"></span>
  </div>
</template>

<script setup>
const props = defineProps({
  title: { type: String, default: '' },
  en: { type: String, default: '' },
  value: { type: [String, Number], default: '-' },
  unit: { type: String, default: '' },
  meta: { type: String, default: '' },
  accent: { type: String, default: 'teal' },
  to: { type: String, default: '' },
  clickable: { type: Boolean, default: false }
})
const emit = defineEmits(['press'])
function go() {
  if (props.to) navigateTo(props.to)
  else if (props.clickable) emit('press')
}
</script>

<style scoped>
.kpi-card {
  position: relative; overflow: hidden;
  background: rgba(255, 255, 255, .74); border: 1px solid rgba(23, 160, 94, .15);
  border-radius: 14px; padding: 13px 16px; backdrop-filter: blur(10px);
  flex: 1; display: flex; flex-direction: column; justify-content: center; min-width: 0;
  transition: border-color .2s, transform .2s;
}
.kpi-card.clickable { cursor: pointer; }
.kpi-card.clickable:hover { border-color: rgba(23, 160, 94, .45); transform: translateY(-2px); }
.kpi-head { display: flex; align-items: baseline; justify-content: space-between; gap: 8px; }
.kpi-title { font-size: 12.5px; font-weight: 600; color: #5C7266; letter-spacing: .02em; }
.kpi-en { font-size: 9.5px; letter-spacing: .12em; color: #8AA294; text-transform: uppercase; white-space: nowrap; }
.kpi-num { display: flex; align-items: baseline; gap: 5px; margin-top: 6px; }
.kpi-num b {
  font-size: 26px; font-weight: 700; letter-spacing: -.02em; color: #1A2B22;
  font-variant-numeric: tabular-nums; font-family: var(--font-num);
}
.kpi-num i { font-style: normal; font-size: 11.5px; color: #5C7266; }
.kpi-meta { margin-top: 6px; font-size: 11px; color: #5C7266; display: flex; align-items: center; gap: 6px; flex-wrap: wrap; }
.kpi-glow { position: absolute; right: -28px; top: -28px; width: 84px; height: 84px; border-radius: 50%; opacity: .16; pointer-events: none; }
.accent-teal .kpi-glow { background: radial-gradient(circle, #17A05E 0%, transparent 70%); }
.accent-cyan .kpi-glow { background: radial-gradient(circle, #2BBFAF 0%, transparent 70%); }
.accent-amber .kpi-glow { background: radial-gradient(circle, #AD570E 0%, transparent 70%); }
.accent-red .kpi-glow { background: radial-gradient(circle, #D64545 0%, transparent 70%); }
.accent-teal .kpi-num b { color: #17A05E; }
.accent-red .kpi-num b { color: #D64545; }
.accent-amber .kpi-num b { color: #AD570E; }
.accent-cyan .kpi-num b { color: #2BBFAF; }
</style>

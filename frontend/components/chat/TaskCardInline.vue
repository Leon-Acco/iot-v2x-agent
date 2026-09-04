<template>
  <!-- 聊天流内嵌任务卡：生成后直接在消息内展示（不跳页），可下载 PDF / 跳处置页 -->
  <div class="tc-inline">
    <div class="tc-badge">
      <svg viewBox="0 0 24 24" width="14" height="14" fill="none" stroke="currentColor" stroke-width="2"><rect x="4" y="4" width="16" height="16" rx="2"/><path d="M8 9h8M8 13h5"/></svg>
      任务卡已生成
    </div>
    <div class="tc-title">{{ title }}</div>
    <div class="tc-status">状态：待处置</div>
    <div class="tc-actions">
      <button class="tc-btn" type="button" @click="download">下载 PDF</button>
      <button class="tc-btn ghost" type="button" @click="goDetail">查看处置页</button>
    </div>
  </div>
</template>

<script setup>
// 内嵌任务卡：props.id = task_card 数据库 id；下载复用 useTaskCards.downloadPdf
const props = defineProps({
  id: { type: [Number, String], required: true },
  title: { type: String, default: '' }
})

const { downloadPdf } = useTaskCards()

async function download() {
  try {
    await downloadPdf(props.id)
  } catch (e) {
    window.alert('下载失败：' + (e.message || e))
  }
}

function goDetail() {
  navigateTo('/anomaly')
}
</script>

<style scoped>
.tc-inline {
  margin: 12px 0; padding: 14px 16px;
  background: rgba(23, 160, 94, .05);
  border: 1px solid rgba(23, 160, 94, .35);
  border-radius: 12px;
}
.tc-badge {
  display: inline-flex; align-items: center; gap: 6px;
  font-size: 11px; font-weight: 600; color: var(--green-ink);
  letter-spacing: .04em; margin-bottom: 6px;
}
.tc-title { font-size: 13.5px; font-weight: 600; color: var(--text-1); }
.tc-status { font-size: 12px; color: var(--text-3); margin: 4px 0 10px; }
.tc-actions { display: flex; gap: 8px; }
.tc-btn {
  height: 28px; padding: 0 14px; border-radius: 999px;
  border: 1px solid rgba(23, 160, 94, .5); background: rgba(23, 160, 94, .1);
  color: var(--green-ink); font: inherit; font-size: 12px; cursor: pointer;
}
.tc-btn:hover { background: rgba(23, 160, 94, .18); }
.tc-btn.ghost { background: #fff; border-color: var(--border-default); color: var(--text-2); }
.tc-btn.ghost:hover { border-color: var(--green-deep); color: var(--green-ink); }
</style>

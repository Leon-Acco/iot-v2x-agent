<template>
  <!-- 任务卡列表：标题 / 能力 / 状态 / 下载 PDF -->
  <PanelCard title="我的任务卡">
    <template #actions>
      <button class="btn refresh-btn" @click="$emit('refresh')">刷新</button>
    </template>
    <div v-if="!cards.length" class="empty">暂无任务卡，可从工作台查询结果一键生成</div>
    <div v-for="c in cards" :key="c.id" class="task-card">
      <div class="task-head">
        <span class="task-title">{{ c.title || c.capability_id }}</span>
        <StatusPill :status="c.status" :label="statusLabel(c.status)" />
      </div>
      <div class="task-meta">
        <span class="mono">{{ c.capability_id }}</span>
        <span>{{ fmtTime(c.created_at) }}</span>
      </div>
      <div class="task-actions">
        <button class="btn op" @click="download(c)">下载 PDF</button>
      </div>
    </div>
  </PanelCard>
</template>

<script setup>
defineProps({
  cards: { type: Array, default: () => [] }
})
const emit = defineEmits(['refresh'])

const { downloadPdf } = useTaskCards()

function statusLabel(s) {
  return { READY: '已生成', DISPATCHED: '已派单', DONE: '已完成' }[s] || s || '-'
}

function fmtTime(t) {
  if (!t) return '-'
  const d = new Date(t)
  return isNaN(d.getTime()) ? String(t) : d.toLocaleString('zh-CN', { hour12: false })
}

async function download(c) {
  try {
    await downloadPdf(c.id)
  } catch (e) {
    alert('PDF 下载失败：' + e.message)
  }
}
</script>

<style scoped>
.empty { color: var(--text-3); font-size: 12px; text-align: center; padding: 20px 8px; line-height: 1.8; }
.refresh-btn { padding: 3px 10px; font-size: 12px; }
.task-card {
  border: 1px solid #F0F0F0; border-radius: 10px; padding: 10px 12px; margin-bottom: 10px;
  background: #FFFFFF;
}
.task-head { display: flex; align-items: center; justify-content: space-between; gap: 8px; }
.task-title { font-size: 13px; font-weight: 600; overflow: hidden; text-overflow: ellipsis; white-space: nowrap; }
.task-meta {
  display: flex; justify-content: space-between; margin-top: 4px;
  font-size: 11px; color: var(--text-3);
}
.mono { font-family: "SF Mono", Consolas, monospace; }
.task-actions { margin-top: 6px; display: flex; justify-content: flex-end; }
.op { padding: 3px 10px; font-size: 12px; }
</style>

<template>
  <!-- 任务卡列表：标题 / 能力 / 状态 / 下载 PDF -->
  <PanelCard title="我的任务卡">
    <template #actions>
      <button class="btn refresh-btn" @click="$emit('refresh')">刷新</button>
    </template>
    <div v-if="!cards.length" class="empty">暂无任务卡，可从工作台查询结果一键生成</div>
    <div v-for="c in cards" :key="c.id" class="task-card" :class="c.status.toLowerCase()">
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
/* 卡片：field 底 + 状态色左肩条（已生成绿/已派单橙/已完成深绿）+ 悬浮微抬升 */
.task-card {
  position: relative; overflow: hidden;
  border: 1px solid var(--line); border-radius: 12px; padding: 12px 14px; margin-bottom: 12px;
  background: #fff;
  box-shadow: 0 2px 8px rgba(13, 64, 38, .05);
  transition: border-color .15s, box-shadow .15s, transform .15s;
}
.task-card::before { content: ""; position: absolute; left: 0; top: 0; bottom: 0; width: 3px; background: var(--t3); }
.task-card.ready::before { background: var(--green); }
.task-card.dispatched::before { background: var(--orange); }
.task-card.done::before { background: var(--green-deep); }
.task-card:hover {
  border-color: rgba(23, 160, 94, .35);
  box-shadow: 0 8px 20px rgba(13, 64, 38, .10);
  transform: translateY(-1px);
}
.task-head { display: flex; align-items: center; justify-content: space-between; gap: 8px; }
.task-title { font-size: 13.5px; font-weight: 700; overflow: hidden; text-overflow: ellipsis; white-space: nowrap; }
.task-meta {
  display: flex; justify-content: space-between; align-items: center; gap: 8px; margin-top: 6px;
  font-size: 11px; color: var(--text-3);
}
.task-meta .mono {
  font-family: "SF Mono", Consolas, monospace;
  background: #fff; border: 1px solid var(--line); border-radius: 6px; padding: 2px 8px;
}
.task-actions { margin-top: 8px; display: flex; justify-content: flex-end; }
/* 下载按钮：绿色 tint 药丸（同 Capability 操作列语言），白底卡面上有颜色焦点 */
.op {
  padding: 4px 12px; font-size: 12px;
  color: var(--green); border-color: rgba(23, 160, 94, .35); background: rgba(23, 160, 94, .08);
}
.op:hover { color: var(--green-deep); border-color: rgba(23, 160, 94, .5); background: rgba(23, 160, 94, .16); }
</style>

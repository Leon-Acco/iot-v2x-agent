<template>
  <!-- P7 反馈列表：赞/踩 + 评论 + 关联运行 -->
  <div>
    <div v-if="!rows.length" class="empty">暂无反馈</div>
    <div v-for="(r, i) in rows" :key="i" class="fb-card">
      <span class="fb-rating" :class="r.rating > 0 ? 'up' : 'down'">{{ r.rating > 0 ? '👍' : '👎' }}</span>
      <div class="fb-body">
        <div class="fb-comment">{{ r.comment || '(无评论)' }}</div>
        <div class="fb-meta">
          <span class="mono">run: {{ (r.run_id || '').slice(0, 12) }}</span>
          <span v-if="r.question" class="fb-q" :title="r.question">{{ r.question }}</span>
          <span>{{ fmt(r.created_at) }}</span>
        </div>
      </div>
    </div>
  </div>
</template>

<script setup>
const api = useApi()
const rows = ref([])

function fmt(t) {
  if (!t) return '-'
  const d = new Date(t)
  return isNaN(d.getTime()) ? String(t) : d.toLocaleString('zh-CN', { hour12: false })
}

onMounted(async () => {
  try {
    const data = await api.get('/admin/feedback')
    rows.value = Array.isArray(data) ? data : []
  } catch (e) { /* 保持空态 */ }
})
</script>

<style scoped>
.empty { text-align: center; color: var(--text-3); padding: 30px 0; }
.fb-card {
  display: flex; gap: 10px; align-items: flex-start;
  border: 1px solid #F0F0F0; border-radius: 10px; padding: 10px 12px; margin-bottom: 8px;
  background: #FFFFFF;
}
.fb-rating { font-size: 16px; }
.fb-rating.down { filter: grayscale(.4); }
.fb-comment { font-size: 13px; line-height: 1.6; }
.fb-meta {
  display: flex; gap: 12px; margin-top: 4px;
  font-size: 11px; color: var(--text-3); flex-wrap: wrap;
}
.mono { font-family: "SF Mono", Consolas, monospace; }
.fb-q { max-width: 300px; overflow: hidden; text-overflow: ellipsis; white-space: nowrap; }
</style>

<template>
  <!-- P3 异常分析与任务卡：左任务列表 + 右异常详情 -->
  <AppShell title="异常分析与任务卡">
    <div class="anomaly-page">
      <div class="left-col">
        <TaskCardList :cards="cards" @refresh="loadMine" />
      </div>
      <div class="right-col">
        <AnomalyCard :context="context" @created="loadMine" />
      </div>
    </div>
  </AppShell>
</template>

<script setup>
const { cards, loadMine } = useTaskCards()
const context = ref(null)

onMounted(() => {
  loadMine()
  // 从工作台跳转带入的异常上下文
  try {
    const raw = sessionStorage.getItem('v2x.anomaly.context')
    if (raw) {
      context.value = JSON.parse(raw)
      sessionStorage.removeItem('v2x.anomaly.context')
    }
  } catch (e) { /* ignore */ }
})
</script>

<style scoped>
.anomaly-page { display: grid; grid-template-columns: minmax(0, 340px) minmax(0, 1fr); gap: 12px; min-height: calc(100vh - 116px); }
.left-col, .right-col { min-width: 0; }
@media (max-width: 880px) {
  .anomaly-page { grid-template-columns: 1fr; }
}
</style>

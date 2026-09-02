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
.anomaly-page { display: flex; gap: 12px; min-height: calc(100vh - 116px); }
.left-col { width: 360px; flex-shrink: 0; }
.right-col { flex: 1; min-width: 0; }
</style>

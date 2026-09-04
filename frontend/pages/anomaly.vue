<template>
  <!-- 异常分析：单列（任务卡列表已并入任务中心 /tasks） -->
  <AppShell title="异常分析">
    <div class="anomaly-page">
      <AnomalyCard :context="context" />
    </div>
  </AppShell>
</template>

<script setup>
const context = ref(null)

onMounted(() => {
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
.anomaly-page { min-height: calc(100vh - 116px); }
@media (max-width: 880px) {
  .anomaly-page { min-height: auto; }
}
</style>

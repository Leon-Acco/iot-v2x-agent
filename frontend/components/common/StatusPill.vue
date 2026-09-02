<template>
  <!-- 状态徽章：胶囊形，浅底深字双色配 -->
  <span class="status-pill" :style="{ color: color, background: bg }">{{ label }}</span>
</template>

<script setup>
const props = defineProps({
  status: { type: String, default: '' },
  label: { type: String, default: '' }
})
const MAP = {
  online: ['#059669', '#ECFDF5', '已上线'],
  staging: ['#D97706', '#FEF3C7', '预发'],
  draft: ['#6B7280', '#F3F4F6', '草稿'],
  deprecated: ['#6B7280', '#F3F4F6', '已下线'],
  PENDING: ['#D97706', '#FEF3C7', '待审批'],
  APPROVED: ['#059669', '#ECFDF5', '已批准'],
  REJECTED: ['#DC2626', '#FEE2E2', '已驳回'],
  REVOKED: ['#6B7280', '#F3F4F6', '已吊销'],
  ACTIVE: ['#059669', '#ECFDF5', '有效']
}
const entry = computed(() => MAP[props.status] || ['#6B7280', '#F3F4F6', props.status || '-'])
const color = computed(() => entry.value[0])
const bg = computed(() => entry.value[1])
const label = computed(() => props.label || entry.value[2])
</script>

<style scoped>
.status-pill {
  display: inline-block; padding: 2px 8px; border-radius: 12px;
  font-size: 11px; font-weight: 500; white-space: nowrap; line-height: 1.6;
}
</style>

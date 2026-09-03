<template>
  <!-- 状态徽章：胶囊形，浅底深字双色配 -->
  <span class="status-pill" :style="{ color: color, background: bg }">{{ label }}</span>
</template>

<script setup>
const props = defineProps({
  status: { type: String, default: '' },
  label: { type: String, default: '' }
})
// v3a sp 系配色：绿/橙/红/灰
const MAP = {
  online: ['#0B7A3C', 'rgba(23, 160, 94, .12)', '已上线'],
  staging: ['#AD570E', 'rgba(232, 122, 30, .13)', '预发'],
  draft: ['#8AA294', '#F5F8F6', '草稿'],
  deprecated: ['#8AA294', '#F5F8F6', '已下线'],
  PENDING: ['#AD570E', 'rgba(232, 122, 30, .13)', '待审批'],
  APPROVED: ['#0B7A3C', 'rgba(23, 160, 94, .12)', '已批准'],
  REJECTED: ['#D64545', 'rgba(214, 69, 69, .1)', '已驳回'],
  REVOKED: ['#8AA294', '#F5F8F6', '已吊销'],
  ACTIVE: ['#0B7A3C', 'rgba(23, 160, 94, .12)', '有效']
}
const entry = computed(() => MAP[props.status] || ['#8AA294', '#F5F8F6', props.status || '-'])
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

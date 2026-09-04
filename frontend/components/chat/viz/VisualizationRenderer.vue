<template>
  <!-- unified visualization card: dispatch by renderer -->
  <div class="viz-card">
    <div class="viz-head">
      <span class="viz-title">{{ vis.title || titleFallback }}</span>
      <span class="viz-head-ops">
        <span v-if="toolBadge" class="viz-tool-badge" :title="'生成工具：' + vis.tool">{{ toolBadge }}</span>
        <span class="viz-badge">{{ badge }}</span>
      </span>
    </div>
    <div v-if="vis.caption" class="viz-caption">{{ vis.caption }}</div>
    <VizEcharts v-if="vis.renderer === 'echarts' && vis.spec" :option="vis.spec" />
    <VizMermaid v-else-if="vis.renderer === 'mermaid'" :code="code" />
    <VizG6 v-else-if="vis.renderer === 'g6'" :data="vis.data" />
    <VizTable v-else-if="vis.renderer === 'table'" :columns="columns" :rows="rows" />
    <VizMap v-else-if="vis.renderer === 'map'" :points="points" />
    <div v-else class="viz-empty">unsupported visualization</div>
  </div>
</template>

<script setup>
const props = defineProps({
  vis: { type: Object, required: true }
})
const columns = computed(() => (props.vis.data && props.vis.data.columns) || [])
const rows = computed(() => (props.vis.data && props.vis.data.rows) || [])
const points = computed(() => (props.vis.data && props.vis.data.points) || [])
const code = computed(() => (props.vis.data && props.vis.data.code) || '')
const badge = computed(() => props.vis.visualizationType || props.vis.renderer)
const titleFallback = computed(() => props.vis.visualizationType || 'visualization')
// 生成工具徽章：后端 VIS_SPEC 带 tool 字段（如 generate_visualization）时展示
const toolBadge = computed(() => props.vis.tool === 'generate_visualization' ? '可视化工具' : '')
</script>

<style scoped>
.viz-card {
  margin: 10px 0; padding: 14px 16px;
  background: #fff; border: 1px solid var(--border-default, #e5e7eb);
  border-radius: 12px;
}
.viz-head { display: flex; align-items: center; justify-content: space-between; margin-bottom: 8px; }
.viz-head-ops { display: inline-flex; align-items: center; gap: 6px; }
.viz-title { font-size: 14px; font-weight: 600; color: var(--text-1, #171717); }
.viz-tool-badge {
  font-size: 11px; color: #1D4ED8;
  background: rgba(37, 99, 235, .08); border-radius: 999px; padding: 2px 10px;
}
.viz-badge {
  font-size: 11px; color: var(--text-3, #a1a1a1);
  border: 1px solid var(--border-default, #e5e7eb); border-radius: 999px; padding: 2px 10px;
}
.viz-caption { font-size: 12px; color: var(--text-3, #737373); margin: -2px 0 8px; line-height: 1.6; }
.viz-empty { color: var(--text-3, #a1a1a1); font-size: 12px; padding: 12px 0; }
</style>

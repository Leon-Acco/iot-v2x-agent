<template>
  <!-- 工具调用叙事：树形行 + 状态图标，完成后折叠为一行 -->
  <div v-if="tools.length" class="tool-narrative">
    <div v-if="allDone" class="tool-collapsed" @click="expanded = !expanded">
      <span class="done-icon">✓</span>
      已完成工具调用（{{ tools.length }} 步）
      <span class="toggle-hint">{{ expanded ? '收起' : '展开过程' }}</span>
    </div>
    <template v-if="!allDone || expanded">
      <div v-for="(t, i) in tools" :key="i" class="tool-row" @click="toggleDetail(i)">
        <span class="tree-prefix">{{ i === tools.length - 1 ? '└─' : '├─' }}</span>
        <span v-if="t.status === 'running'" class="spinner"></span>
        <span v-else-if="t.status === 'done'" class="done-icon">✓</span>
        <span v-else class="error-icon">✕</span>
        <span class="tool-name">{{ t.name }}</span>
        <span v-if="t.summary" class="tool-summary">{{ t.summary }}</span>
        <div v-if="detailIndex === i" class="tool-detail" @click.stop>
          <div v-if="t.args" class="detail-block">
            <div class="detail-label">参数</div>
            <pre>{{ pretty(t.args) }}</pre>
          </div>
          <div v-if="t.result && t.result.stats && t.result.stats.sqlSnapshot" class="detail-block">
            <div class="detail-label">SQL</div>
            <pre>{{ t.result.stats.sqlSnapshot }}</pre>
          </div>
        </div>
      </div>
    </template>
  </div>
</template>

<script setup>
const props = defineProps({
  tools: { type: Array, default: () => [] }
})
const expanded = ref(false)
const detailIndex = ref(-1)

const allDone = computed(() =>
  props.tools.length > 0 && props.tools.every(t => t.status !== 'running')
)

function toggleDetail(i) {
  detailIndex.value = detailIndex.value === i ? -1 : i
}

function pretty(obj) {
  try { return JSON.stringify(obj, null, 2) } catch (e) { return String(obj) }
}
</script>

<style scoped>
.tool-narrative { margin: 8px 0; font-size: 13px; }
.tool-collapsed {
  display: inline-flex; align-items: center; gap: 6px;
  color: var(--text-3); cursor: pointer; padding: 4px 8px; border-radius: 6px;
}
.tool-collapsed:hover { background: var(--primary-light); }
.toggle-hint { font-size: 11px; color: var(--text-3); }
.tool-row {
  display: flex; align-items: center; flex-wrap: wrap; gap: 6px;
  padding: 3px 0; color: var(--text-2); cursor: pointer;
  font-family: "SF Mono", Consolas, monospace; font-size: 12px;
}
.tree-prefix { color: var(--line-strong); }
.tool-name { color: var(--text-1); font-weight: 600; }
.tool-summary { color: var(--text-3); }
.spinner {
  width: 11px; height: 11px; border-radius: 50%;
  border: 2px solid var(--line-strong); border-top-color: var(--primary);
  animation: spin .8s linear infinite;
}
@keyframes spin { to { transform: rotate(360deg); } }
.done-icon { color: var(--success); font-weight: 700; }
.error-icon { color: var(--danger); font-weight: 700; }
.tool-detail {
  width: 100%; margin: 4px 0 8px 22px; padding: 8px 10px;
  background: var(--field); border-radius: 8px; border: 1px solid var(--line);
}
.detail-label { font-size: 11px; color: var(--text-3); margin-bottom: 4px; }
.tool-detail pre {
  margin: 0; font-size: 11px; white-space: pre-wrap; word-break: break-all;
  max-height: 180px; overflow-y: auto; color: var(--text-2);
}
</style>

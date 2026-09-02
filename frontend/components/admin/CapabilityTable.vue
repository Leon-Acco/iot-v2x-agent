<template>
  <!-- P4 能力列表：搜索/状态筛选由父级处理，本组件只负责渲染与事件 -->
  <table class="cap-table">
    <thead>
      <tr>
        <th>ID</th>
        <th>显示名</th>
        <th>域</th>
        <th>类型</th>
        <th>状态</th>
        <th>版本</th>
        <th>操作</th>
      </tr>
    </thead>
    <tbody>
      <tr v-for="c in items" :key="c.id">
        <td class="mono">{{ c.id }}</td>
        <td>
          <div class="cap-name">{{ c.display }}</div>
          <div class="cap-aliases">{{ (c.aliases || []).join(' / ') }}</div>
        </td>
        <td>{{ c.domain }}</td>
        <td><span class="kind-tag" :class="c.kind">{{ c.kind === 'orchestration' ? '编排' : '能力' }}</span></td>
        <td><StatusPill :status="c.status" /></td>
        <td class="mono">v{{ c.version }}</td>
        <td class="ops">
          <button class="btn op" @click="$emit('edit', c)">编辑</button>
          <button class="btn op" @click="$emit('dryrun', c)">试跑</button>
          <button v-if="c.status !== 'online'" class="btn op up" @click="$emit('status', c, 'online')">上线</button>
          <button v-else class="btn op down" @click="$emit('status', c, 'deprecated')">下线</button>
        </td>
      </tr>
    </tbody>
  </table>
</template>

<script setup>
defineProps({
  items: { type: Array, default: () => [] }
})
defineEmits(['edit', 'dryrun', 'status'])
</script>

<style scoped>
.cap-table { width: 100%; border-collapse: collapse; font-size: 13px; }
.cap-table th {
  text-align: left; padding: 10px 12px; color: var(--text-3); font-size: 12px;
  border-bottom: 1px solid #E5E5E5; font-weight: 600;
}
.cap-table td { padding: 10px 12px; border-bottom: 1px solid #F3F4F6; vertical-align: middle; }
.cap-table tbody tr:hover { background: #F3F3F5; }
.mono { font-family: "SF Mono", Consolas, monospace; font-size: 12px; color: var(--text-2); }
.cap-name { font-weight: 600; }
.cap-aliases { font-size: 11px; color: var(--text-3); margin-top: 2px; }
.kind-tag { font-size: 11px; padding: 2px 8px; border-radius: 4px; background: #F3F4F6; color: var(--text-2); }
.kind-tag.orchestration { background: #EFF6FF; color: #4F46E5; }
.ops { white-space: nowrap; }
.op { padding: 4px 10px; font-size: 12px; }
.op.up { color: var(--success); }
.op.up:hover { background: rgba(34,197,94,.1); color: var(--success); }
.op.down { color: var(--danger); }
.op.down:hover { background: rgba(239,68,68,.08); color: var(--danger); }
</style>

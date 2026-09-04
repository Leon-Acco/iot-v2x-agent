<template>
  <!-- 能力列表：搜索/状态/域筛选由父级处理，本组件只负责渲染与事件 -->
  <!-- cap-scroll: min-width floor + horizontal scroll on narrow screens (merged from prod) -->
  <div class="cap-scroll">
  <table class="cap-table">
    <thead>
      <tr>
        <th>ID</th>
        <th>显示名</th>
        <th>域</th>
        <th>类型</th>
        <th>状态</th>
        <th class="ver">版本</th>
        <th>完整度</th>
        <th class="ops-col">操作</th>
      </tr>
    </thead>
    <tbody>
      <tr v-for="c in items" :key="c.id">
        <td class="mono">{{ c.id }}</td>
        <td>
          <div class="cap-name">
            {{ c.display }}
            <span v-if="c.owner === 'AI'" class="ai-tag">AI</span>
          </div>
          <div class="cap-aliases">{{ (c.aliases || []).join(' / ') }}</div>
        </td>
        <td><span class="domain-tag" :style="domainStyle(c.domain)">{{ c.domain }}</span></td>
        <td><span class="kind-tag" :class="c.kind">{{ c.kind === 'orchestration' ? '编排' : '能力' }}</span></td>
        <td><StatusPill :status="c.status" /></td>
        <td class="mono ver">v{{ c.version }}</td>
        <td>
          <span v-if="lintMap[c.id]" class="score-pill" :class="scoreClass(lintMap[c.id].score)" :title="lintMap[c.id].issues.join('\n')">
            {{ lintMap[c.id].score }}
          </span>
          <span v-else class="score-none">-</span>
        </td>
        <td class="ops">
          <button class="op view" @click="$emit('detail', c)">详情</button>
          <button class="op" @click="$emit('edit', c)">编辑</button>
          <button class="op" @click="$emit('dryrun', c)">试跑</button>
          <button class="op" @click="$emit('history', c)">历史</button>
          <button v-if="c.status !== 'online'" class="op up" @click="$emit('status', c, 'online')">上线</button>
          <button v-else class="op down" @click="$emit('status', c, 'deprecated')">下线</button>
        </td>
      </tr>
      <tr v-if="!items.length">
        <td colspan="8" class="empty-cell">暂无符合条件的能力</td>
      </tr>
    </tbody>
  </table>
  </div>
</template>

<script setup>
defineProps({
  items: { type: Array, default: () => [] },
  lintMap: { type: Object, default: () => ({}) }
})
defineEmits(['edit', 'dryrun', 'status', 'history', 'detail'])

function scoreClass(s) {
  if (s >= 85) return 'good'
  if (s >= 60) return 'mid'
  return 'bad'
}

// 域标签颜色：按域名哈希取色，便于扫描分组
const palette = [
  ['#eaf6f1', '#0f8a6a'], ['#eef2ff', '#4F46E5'], ['#fef3c7', '#b45309'],
  ['#fce7f3', '#be185d'], ['#e0f2fe', '#0369a1'], ['#f3e8ff', '#7e22ce'],
  ['#ecfccb', '#4d7c0f'], ['#fee2e2', '#b91c1c']
]
function domainStyle(domain) {
  const d = domain || ''
  let h = 0
  for (let i = 0; i < d.length; i++) h = (h * 31 + d.charCodeAt(i)) >>> 0
  const [bg, color] = palette[h % palette.length]
  return { background: bg, color }
}
</script>

<style scoped>
.cap-scroll { overflow-x: auto; -webkit-overflow-scrolling: touch; }
.cap-table { width: 100%; min-width: 760px; border-collapse: collapse; font-size: 13px; }
.cap-table th {
  text-align: left; padding: 10px 12px; color: var(--text-3); font-size: 12px;
  border-bottom: 1px solid #E5E5E5; font-weight: 600; white-space: nowrap;
}
.cap-table td { padding: 10px 12px; border-bottom: 1px solid #F3F4F6; vertical-align: middle; white-space: nowrap; }
.cap-table tbody tr { transition: background .15s ease; }
.cap-table tbody tr:hover { background: #F7FAF9; }
.mono { font-family: "SF Mono", Consolas, monospace; font-size: 12px; color: var(--text-2); }
.ver { min-width: 88px; }
.cap-name { font-weight: 600; display: flex; align-items: center; gap: 6px; }
.ai-tag {
  font-size: 10px; font-weight: 700; padding: 1px 6px; border-radius: 4px;
  background: linear-gradient(135deg, #eef2ff, #e0e7ff); color: #4F46E5;
  border: 1px solid #c7d2fe;
}
.cap-aliases { font-size: 11px; color: var(--text-3); margin-top: 2px; }
.domain-tag { font-size: 11px; padding: 2px 8px; border-radius: 4px; font-weight: 500; }
.kind-tag { font-size: 11px; padding: 2px 8px; border-radius: 4px; background: #F3F4F6; color: var(--text-2); }
.kind-tag.orchestration { background: #eaf6f1; color: #4F46E5; }
.ops-col { width: 300px; }
.score-pill { font-size: 11px; font-weight: 600; padding: 2px 9px; border-radius: 9px; cursor: default; }
.score-pill.good { background: var(--primary-light); color: var(--primary-deep); }
.score-pill.mid { background: #fffbeb; color: #b45309; }
.score-pill.bad { background: #fee2e2; color: #b91c1c; }
.score-none { font-size: 11px; color: var(--text-3); }
/* 操作按钮三档层次：详情=靛蓝软底 / 例行动作=灰软底 / 上线下线=状态色软底，彼此 6px 间距 */
.ops { white-space: nowrap; }
.op {
  height: 26px; padding: 0 11px; margin-left: 6px; border-radius: 999px;
  border: none; background: #F3F4F6; color: var(--text-2);
  font: inherit; font-size: 12px; cursor: pointer;
  display: inline-flex; align-items: center;
  transition: background .15s ease, color .15s ease;
}
.op:first-child { margin-left: 0; }
.op:hover { background: rgba(13, 64, 38, .09); color: var(--text-1); }
.op.view { background: #eef2ff; color: #4F46E5; }
.op.view:hover { background: #e0e7ff; color: #4338ca; }
.op.up { background: rgba(23, 160, 94, .12); color: var(--success); font-weight: 600; }
.op.up:hover { background: rgba(23, 160, 94, .22); color: var(--success); }
.op.down { background: rgba(214, 69, 69, .1); color: var(--danger); font-weight: 600; }
.op.down:hover { background: rgba(214, 69, 69, .18); color: var(--danger); }
.empty-cell { text-align: center; color: var(--text-3); padding: 36px 0; }
</style>

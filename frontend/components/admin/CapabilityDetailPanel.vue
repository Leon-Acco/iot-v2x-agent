<template>
  <!-- 能力详情：客户视角的工具说明书（用途/参数/预期数据/质量问题） -->
  <div class="detail-panel">
    <div class="d-head">
      <div>
        <div class="d-name">{{ capability.display }}</div>
        <div class="d-id mono">{{ capability.id }} · v{{ capability.version }}</div>
      </div>
      <div class="d-badges">
        <span v-if="lint" class="score-pill" :class="scoreClass">完整度 {{ lint.score }}</span>
        <StatusPill :status="capability.status" />
      </div>
    </div>

    <section class="d-section">
      <div class="d-title">用途说明</div>
      <p class="d-desc">{{ capability.description || '暂无描述' }}</p>
      <div class="d-chips">
        <span v-for="a in capability.aliases || []" :key="a" class="chip">{{ a }}</span>
      </div>
      <div v-if="(capability.sampleQuestions || []).length" class="d-samples">
        <div class="d-subtitle">示例问题</div>
        <div v-for="q in capability.sampleQuestions" :key="q" class="sample-q">{{ q }}</div>
      </div>
    </section>

    <section class="d-section">
      <div class="d-title">参数说明</div>
      <table v-if="(capability.params || []).length" class="d-table">
        <thead><tr><th>参数</th><th>类型</th><th>必填</th><th>说明</th></tr></thead>
        <tbody>
          <tr v-for="p in capability.params" :key="p.name">
            <td class="mono">{{ p.name }}</td>
            <td>{{ p.type }}</td>
            <td>{{ p.required ? '是' : '否' }}</td>
            <td>{{ p.description || '-' }}</td>
          </tr>
        </tbody>
      </table>
      <div v-else class="d-empty">无参数</div>
    </section>

    <section class="d-section">
      <div class="d-title">预期返回数据</div>
      <table v-if="columns.length" class="d-table">
        <thead><tr><th>列</th><th>显示名</th><th>语义</th><th>单位</th></tr></thead>
        <tbody>
          <tr v-for="c in columns" :key="c.name">
            <td class="mono">{{ c.name }}</td>
            <td>{{ c.display || '-' }}</td>
            <td><span class="sem-tag">{{ c.semantic }}</span></td>
            <td>{{ c.unit || '-' }}</td>
          </tr>
        </tbody>
      </table>
      <div v-else class="d-empty">未定义返回列</div>
      <div class="d-meta">
        <div class="m-item"><span>来源表</span>{{ (capability.sourceTables || []).join(', ') || '-' }}</div>
        <div class="m-item"><span>时效</span>{{ freshnessText }}</div>
        <div class="m-item"><span>行数上限</span>{{ (capability.limits && capability.limits.maxRows) || '-' }}</div>
        <div class="m-item"><span>图表</span>{{ capability.chartHint || '-' }}</div>
      </div>
    </section>

    <section v-if="lint && lint.issues.length" class="d-section lint-section">
      <div class="d-title">质量问题（{{ lint.issues.length }}）</div>
      <div v-for="(iss, i) in lint.issues" :key="i" class="lint-item">{{ iss }}</div>
    </section>

    <div class="d-actions">
      <button class="btn" @click="$emit('history', capability)">版本历史</button>
      <button class="btn" @click="$emit('edit', capability)">编辑</button>
      <button class="btn btn-primary" @click="$emit('dryrun', capability)">试跑</button>
    </div>
  </div>
</template>

<script setup>
const props = defineProps({
  capability: { type: Object, required: true },
  lint: { type: Object, default: null }
})
defineEmits(['dryrun', 'edit', 'history'])

const columns = computed(() => (props.capability.returns && props.capability.returns.columns) || [])
const scoreClass = computed(() => {
  if (!props.lint) return ''
  if (props.lint.score >= 85) return 'good'
  if (props.lint.score >= 60) return 'mid'
  return 'bad'
})
const freshnessText = computed(() => {
  const fp = props.capability.freshnessPolicy
  if (!fp) return '-'
  const map = { realtime: '实时', t_plus_0: 'T+0', t_plus_1: 'T+1' }
  return (map[fp.type] || fp.type) + '（预期延迟 ' + (fp.expectedDelayMin || 0) + ' 分钟）'
})
</script>

<style scoped>
.detail-panel { display: flex; flex-direction: column; gap: 14px; }
.d-head { display: flex; justify-content: space-between; align-items: flex-start; }
.d-name { font-size: 17px; font-weight: 700; }
.d-id { font-size: 12px; color: var(--text-3); margin-top: 3px; }
.mono { font-family: "SF Mono", Consolas, monospace; }
.d-badges { display: flex; gap: 8px; align-items: center; }
.score-pill { font-size: 11px; font-weight: 600; padding: 3px 10px; border-radius: 10px; }
.score-pill.good { background: var(--primary-light); color: var(--primary-deep); }
.score-pill.mid { background: #fffbeb; color: #b45309; }
.score-pill.bad { background: #fee2e2; color: #b91c1c; }
.d-section { border: 1px solid #EEF2F0; border-radius: 12px; padding: 12px 14px; background: #FBFCFC; }
.d-title { font-size: 13px; font-weight: 600; margin-bottom: 8px; }
.d-subtitle { font-size: 12px; color: var(--text-3); margin: 10px 0 6px; }
.d-desc { font-size: 13px; color: var(--text-2); line-height: 1.7; margin: 0 0 8px; }
.d-chips { display: flex; flex-wrap: wrap; gap: 6px; }
.chip { font-size: 11px; background: var(--primary-light); color: var(--primary-deep); padding: 3px 8px; border-radius: 6px; }
.sample-q {
  font-size: 12px; color: var(--text-2); padding: 6px 10px; margin-bottom: 4px;
  background: #fff; border: 1px solid #EEF2F0; border-radius: 8px;
}
.d-table { width: 100%; border-collapse: collapse; font-size: 12px; background: #fff; border-radius: 8px; overflow: hidden; }
.d-table th { text-align: left; padding: 7px 10px; color: var(--text-3); font-weight: 600; border-bottom: 1px solid #EEF2F0; }
.d-table td { padding: 7px 10px; border-bottom: 1px solid #F7F8FA; }
.sem-tag { font-size: 11px; padding: 1px 7px; border-radius: 4px; background: #eef2ff; color: #4F46E5; }
.d-empty { font-size: 12px; color: var(--text-3); padding: 8px 0; }
.d-meta { display: grid; grid-template-columns: repeat(2, 1fr); gap: 8px; margin-top: 10px; }
.m-item { background: #fff; border: 1px solid #EEF2F0; border-radius: 8px; padding: 7px 10px; font-size: 12px; display: flex; flex-direction: column; gap: 2px; }
.m-item span { font-size: 11px; color: var(--text-3); }
.lint-section { background: #fffdf5; border-color: #fde68a; }
.lint-item { font-size: 12px; color: #92400e; padding: 4px 0; }
.lint-item::before { content: "⚠ "; }
.d-actions { display: flex; gap: 10px; justify-content: flex-end; }
</style>

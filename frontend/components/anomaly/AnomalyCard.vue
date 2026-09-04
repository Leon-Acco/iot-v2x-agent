<template>
  <!-- 异常分析详情：结论 + 参数快照 + 处置建议 + 存为巡检任务（任务中心链路） -->
  <div class="anomaly-card panel-card">
    <template v-if="context">
      <div class="section-title">异常解释结论</div>
      <div class="conclusion">{{ context.conclusion || '暂无结论文本' }}</div>

      <div class="section-title">关联能力与参数快照</div>
      <div class="meta-row">
        <span class="pill mono">{{ context.capabilityId }}</span>
        <span v-if="context.runId" class="pill mono">run: {{ context.runId.slice(0, 8) }}</span>
      </div>
      <pre v-if="paramsText" class="params-view">{{ paramsText }}</pre>

      <div class="section-title">处置建议</div>
      <ul class="suggestions">
        <li v-for="(s, i) in suggestions" :key="i">{{ s }}</li>
      </ul>

      <div class="action-bar">
        <button class="btn btn-primary" :disabled="creating" @click="createCard">
          {{ creating ? '保存中…' : '存为巡检任务' }}
        </button>
        <span v-if="createdId" class="created-tip">巡检任务 #{{ createdId }} 已存入任务中心，可一键重跑或定时执行</span>
        <NuxtLink v-if="createdId" to="/tasks" class="btn">去任务中心</NuxtLink>
      </div>
      <div v-if="error" class="error-text">{{ error }}</div>
    </template>

    <div v-else class="no-context">
      <p>暂无异常上下文</p>
      <p class="hint">在运营工作台对车辆异常提问后，从回答下方「存为任务」即可固化巡检流程</p>
      <NuxtLink to="/chat" class="btn btn-primary">去工作台提问</NuxtLink>
    </div>
  </div>
</template>

<script setup>
const props = defineProps({
  context: { type: Object, default: null }
})
const emit = defineEmits(['created'])

const { createFromFrames } = useTasks()
const creating = ref(false)
const createdId = ref(null)
const error = ref('')

const paramsText = computed(() => {
  const p = props.context && props.context.params
  if (!p || !Object.keys(p).length) return ''
  try { return JSON.stringify(p, null, 2) } catch (e) { return '' }
})

// 默认处置建议（面向设备类异常）
const suggestions = computed(() => [
  '派单值班运维确认终端供电与网络状态',
  '联系司机或场站现场检查设备',
  '持续观察 24 小时，未恢复则升级为维修工单'
])

/** 存为巡检任务（任务中心链路）：能力 + 参数固化为单步任务，之后可重跑/定时/导出 PDF */
async function createCard() {
  if (!props.context || creating.value) return
  creating.value = true
  error.value = ''
  try {
    const args = {}
    const p = props.context.params || {}
    Object.keys(p).filter(k => !k.startsWith('_') && !k.startsWith('acl_')).forEach(k => { args[k] = p[k] })
    const res = await createFromFrames({
      title: (props.context.title || props.context.capabilityId || '异常巡检').slice(0, 60),
      steps: [{ id: props.context.capabilityId, name: props.context.capabilityId, args }],
      sourceQuestion: (props.context.conclusion || '').slice(0, 200)
    })
    createdId.value = res && res.id ? res.id : null
    emit('created')
  } catch (e) {
    error.value = e.message || '保存失败'
  } finally {
    creating.value = false
  }
}
</script>

<style scoped>
.anomaly-card { padding: 20px 22px; }
.section-title { font-size: 13px; font-weight: 700; margin: 18px 0 8px; }
.section-title:first-child { margin-top: 0; }
.conclusion {
  font-size: 13px; line-height: 1.9; color: var(--text-1);
  white-space: pre-wrap; word-break: break-word;
  background: #F3F3F5; border-radius: 10px; padding: 14px 16px;
}
.meta-row { display: flex; gap: 6px; flex-wrap: wrap; }
.pill { font-size: 11px; color: var(--text-3); background: #F3F4F6; border-radius: 20px; padding: 3px 10px; }
.mono { font-family: "SF Mono", Consolas, monospace; }
.params-view {
  margin: 8px 0 0; background: #111827; color: #E5E7EB; font-size: 11px;
  padding: 10px; border-radius: 8px; white-space: pre-wrap; max-height: 180px; overflow-y: auto;
}
.suggestions { margin: 0; padding-left: 18px; font-size: 13px; line-height: 2; color: var(--text-2); }
.action-bar { display: flex; align-items: center; gap: 10px; margin-top: 18px; }
.created-tip { font-size: 12px; color: var(--success); }
.error-text { color: var(--danger); font-size: 12px; margin-top: 10px; }
.no-context { text-align: center; padding: 60px 20px; color: var(--text-2); }
.no-context .hint { font-size: 12px; color: var(--text-3); margin: 8px 0 18px; }
</style>

<template>
  <!-- 参数微调：基于上次调用的实际参数生成表单，重跑不经过模型 -->
  <div class="param-tuner">
    <div v-if="!entries.length" class="empty">当前结果没有可调参数</div>
    <template v-else>
      <div v-for="e in entries" :key="e.key" class="param-field">
        <label>{{ e.key }}</label>
        <input v-model="e.value" :type="e.numeric ? 'number' : 'text'" />
      </div>
      <div class="tuner-tip">修改参数后重跑，不经过模型</div>
      <button class="btn btn-primary rerun-btn" :disabled="running" @click="doRerun">
        {{ running ? '重跑中…' : '重跑' }}
      </button>
      <div v-if="error" class="tuner-error">{{ error }}</div>
    </template>
  </div>
</template>

<script setup>
const props = defineProps({
  stream: { type: Object, required: true }
})

const entries = ref([])
const running = ref(false)
const error = ref('')

// 从最后一次工具调用取得 capabilityId 与实际参数
const lastTool = computed(() => {
  const list = props.stream.tools.value
  for (let i = list.length - 1; i >= 0; i--) {
    if (list[i].id) return list[i]
  }
  return null
})

watch(lastTool, (t) => {
  const args = t && t.args && typeof t.args === 'object' ? t.args : {}
  // 优先按后端下发的 _editable 白名单过滤，排除派生展示字段
  const whitelist = Array.isArray(args._editable) ? args._editable : null
  entries.value = Object.entries(args)
    .filter(([key]) => !key.startsWith('_') && !key.startsWith('acl_'))
    .filter(([key]) => !whitelist || whitelist.includes(key))
    .map(([key, value]) => ({
      key,
      value: value == null ? '' : String(value),
      numeric: typeof value === 'number'
    }))
}, { immediate: true })

async function doRerun() {
  const t = lastTool.value
  if (!t || running.value) return
  running.value = true
  error.value = ''
  const params = {}
  for (const e of entries.value) {
    params[e.key] = e.numeric && e.value !== '' ? Number(e.value) : e.value
  }
  try {
    await props.stream.rerun(t.id, params)
  } catch (e) {
    error.value = e.message || '重跑失败'
  } finally {
    running.value = false
  }
}
</script>

<style scoped>
.param-tuner { padding: 4px 0; }
.empty { color: var(--text-3); font-size: 12px; text-align: center; padding: 20px 0; }
.param-field { display: flex; align-items: center; gap: 8px; margin-bottom: 10px; }
.param-field label {
  width: 110px; flex-shrink: 0; font-size: 12px; color: var(--text-2);
  overflow: hidden; text-overflow: ellipsis; white-space: nowrap;
}
.param-field input {
  flex: 1; min-width: 0; padding: 7px 10px; font-size: 12px;
  border: 1px solid #E5E5E5; border-radius: 8px; outline: none;
  background: #F3F3F5;
}
.param-field input:focus { border-color: var(--primary); }
.tuner-tip { font-size: 11px; color: var(--text-3); margin: 6px 0 10px; }
.rerun-btn { width: 100%; justify-content: center; }
.tuner-error { color: var(--danger); font-size: 12px; margin-top: 8px; }
</style>

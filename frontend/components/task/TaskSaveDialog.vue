<template>
  <!-- 存为任务对话框：把本轮工具帧整链固化为可执行任务（重复/一键/定时） -->
  <Teleport to="body">
    <div v-if="modelValue" class="save-mask" @click="close">
      <div class="save-box" @click.stop>
        <div class="save-title">存为可执行任务</div>
        <div class="save-hint">
          把本轮查询流程固化为任务，之后可在任务中心一键重跑、定时执行。
          时间范围按相对词保存（如「近7天」），每次执行自动按当天现算。
        </div>

        <label class="field-label">任务标题</label>
        <input v-model="title" class="title-input" maxlength="60" placeholder="默认取你的提问" />

        <label class="field-label">将固化的步骤（{{ frames.length }} 个工具调用）</label>
        <div class="frames">
          <div v-for="(f, i) in frames" :key="i" class="frame-row">
            <span class="frame-seq">{{ i + 1 }}</span>
            <span class="frame-name">{{ f.name || f.id }}</span>
            <span class="frame-args">{{ argsBrief(f.args) }}</span>
          </div>
        </div>

        <label class="analyze-toggle">
          <input v-model="analyzeReport" type="checkbox" />
          <span>每次执行后自动生成分析报告（基于各步数据由分析模型产出结论与建议）</span>
        </label>

        <div v-if="skipped.length" class="skipped-tip">
          有 {{ skipped.length }} 个工具不支持固化已跳过：<br />
          <span v-for="(s, i) in skipped" :key="i">· {{ s.displayName || s.tool }}（{{ s.reason }}）</span>
        </div>
        <div v-if="error" class="error-tip">{{ error }}</div>

        <div class="save-actions">
          <button class="btn" :disabled="saving" @click="close">取消</button>
          <button class="btn btn-primary" :disabled="saving" @click="save">
            {{ saving ? '保存中…' : '保存任务' }}
          </button>
        </div>
      </div>
    </div>
  </Teleport>
</template>

<script setup>
// props.frames = 本轮全部工具帧 [{id, name, args}]（AiMessage 预清洗过 _/acl_ 前缀）
const props = defineProps({
  modelValue: { type: Boolean, default: false },
  frames: { type: Array, default: () => [] },
  defaultTitle: { type: String, default: '' },
  sourceSessionId: { type: String, default: '' },
  sourceQuestion: { type: String, default: '' }
})
const emit = defineEmits(['update:modelValue', 'saved'])

const { createFromFrames } = useTasks()
const title = ref('')
const saving = ref(false)
const skipped = ref([])
const error = ref('')
/** 执行后生成分析报告开关（后端默认开，这里给用户显式控制权） */
const analyzeReport = ref(true)

watch(() => props.modelValue, (open) => {
  if (open) {
    title.value = props.defaultTitle || props.sourceQuestion || ''
    analyzeReport.value = true
    skipped.value = []
    error.value = ''
  }
})

function argsBrief(args) {
  if (!args || typeof args !== 'object') return ''
  const parts = []
  if (args.vehicle) parts.push(args.vehicle)
  const t = args.time_range || args.time_range_display
  if (t) parts.push(t)
  if (Array.isArray(args.vin_list) && args.vin_list.length) parts.push(args.vin_list.length + ' 台车')
  return parts.join(' · ')
}

async function save() {
  if (saving.value) return
  saving.value = true
  error.value = ''
  try {
    const res = await createFromFrames({
      title: title.value || props.sourceQuestion,
      steps: props.frames.map(f => ({ id: f.id, name: f.name, args: f.args || {} })),
      sourceSessionId: props.sourceSessionId,
      sourceQuestion: props.sourceQuestion,
      analyzeReport: analyzeReport.value
    })
    skipped.value = res && Array.isArray(res.skipped) ? res.skipped : []
    if (res && res.id) {
      emit('saved', res)
      emit('update:modelValue', false)
    }
  } catch (e) {
    error.value = e.message || '保存失败'
  } finally {
    saving.value = false
  }
}

function close() {
  emit('update:modelValue', false)
}
</script>

<style scoped>
.save-mask {
  position: fixed; inset: 0; z-index: 120;
  background: rgba(23, 23, 23, 0.32); display: flex; align-items: center; justify-content: center;
}
.save-box {
  width: 460px; max-width: calc(100vw - 32px); max-height: 82vh; overflow-y: auto;
  padding: 24px; background: #fff; border-radius: 16px; box-shadow: var(--shadow-modal);
}
.save-title { font-size: 16px; font-weight: 600; margin-bottom: 8px; }
.save-hint { font-size: 12px; color: var(--text-3); line-height: 1.7; margin-bottom: 16px; }
.field-label { display: block; font-size: 12px; font-weight: 600; color: var(--text-2); margin: 12px 0 6px; }
.title-input {
  width: 100%; height: 36px; padding: 0 12px; font: inherit; font-size: 13px;
  border: 1px solid var(--border-default); border-radius: 10px; outline: none;
}
.title-input:focus { border-color: var(--green); }
.analyze-toggle {
  display: flex; align-items: flex-start; gap: 8px; margin-top: 12px;
  font-size: 12px; color: var(--text-2); line-height: 1.6; cursor: pointer;
}
.analyze-toggle input { margin-top: 2px; accent-color: var(--green); }
.frames { border: 1px solid var(--line); border-radius: 12px; overflow: hidden; }
.frame-row {
  display: flex; align-items: center; gap: 10px; padding: 9px 12px;
  border-bottom: 1px solid var(--line); font-size: 12px;
}
.frame-row:last-child { border-bottom: none; }
.frame-seq {
  width: 18px; height: 18px; border-radius: 50%; flex-shrink: 0;
  background: rgba(23, 160, 94, .12); color: var(--green-deep);
  font-size: 11px; font-weight: 700; display: inline-flex; align-items: center; justify-content: center;
}
.frame-name { font-weight: 600; color: var(--text-1); flex-shrink: 0; }
.frame-args {
  color: var(--text-3); overflow: hidden; text-overflow: ellipsis; white-space: nowrap;
  font-family: "SF Mono", Consolas, monospace; font-size: 11px;
}
.skipped-tip {
  margin-top: 12px; font-size: 12px; color: var(--orange, #D97706);
  background: rgba(217, 119, 6, .07); border-radius: 10px; padding: 10px 12px; line-height: 1.8;
}
.error-tip { margin-top: 12px; font-size: 12px; color: var(--danger); }
.save-actions { display: flex; justify-content: flex-end; gap: 8px; margin-top: 18px; }
</style>

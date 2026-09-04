<template>
  <!-- 定时设置对话框：cron 预设 + 自定义 + 未来 3 次触发预览 + 启停 -->
  <Teleport to="body">
    <div v-if="modelValue" class="sch-mask" @click="close">
      <div class="sch-box" @click.stop>
        <div class="sch-title">定时执行设置</div>
        <div class="sch-hint">
          定时执行按<b>创建者当前权限</b>重放（权限变更自动生效）；执行结果可在「历史」中查看。
        </div>

        <label class="field-label">执行频率</label>
        <div class="preset-row">
          <button
            v-for="p in PRESETS" :key="p.expr"
            class="preset-btn" :class="{ on: cronExpr === p.expr }"
            type="button" @click="pick(p)"
          >{{ p.label }}</button>
        </div>

        <template v-if="customMode">
          <label class="field-label">cron 表达式（6 位，Asia/Shanghai）</label>
          <input v-model="cronExpr" class="cron-input" placeholder="秒 分 时 日 月 周，如 0 0 8 * * *" @input="preview = []" />
        </template>

        <div v-if="cronExpr" class="preview-row">
          <button class="btn preview-btn" :disabled="previewing" @click="doPreview">
            {{ previewing ? '计算中…' : '预览触发时间' }}
          </button>
          <div v-if="preview.length" class="preview-list">
            <div v-for="(p, i) in preview" :key="i" class="preview-item">{{ p }}</div>
          </div>
        </div>

        <div v-if="error" class="error-tip">{{ error }}</div>

        <div class="sch-actions">
          <button v-if="task.schedule_enabled" class="btn" :disabled="submitting" @click="disable">停用定时</button>
          <span style="flex:1"></span>
          <button class="btn" :disabled="submitting" @click="close">取消</button>
          <button class="btn btn-primary" :disabled="submitting || !cronExpr" @click="enable">
            {{ submitting ? '保存中…' : (task.schedule_enabled ? '更新定时' : '启用定时') }}
          </button>
        </div>
      </div>
    </div>
  </Teleport>
</template>

<script setup>
const props = defineProps({
  modelValue: { type: Boolean, default: false },
  // 任务行（来自 /ag-ui/task/mine）：{id, cron_expr, schedule_enabled, ...}
  task: { type: Object, default: null }
})
const emit = defineEmits(['update:modelValue', 'saved'])

const { setSchedule, previewCron } = useTasks()
const cronExpr = ref('')
const customMode = ref(false)
const preview = ref([])
const previewing = ref(false)
const submitting = ref(false)
const error = ref('')

const PRESETS = [
  { label: '每天 08:00', expr: '0 0 8 * * *' },
  { label: '每周一 08:00', expr: '0 0 8 ? * MON' },
  { label: '每小时整点', expr: '0 0 * * * *' },
  { label: '自定义…', expr: '' }
]

watch(() => props.modelValue, (open) => {
  if (open) {
    error.value = ''
    preview.value = []
    const existing = props.task && props.task.cron_expr
    if (existing) {
      cronExpr.value = existing
      customMode.value = !PRESETS.some(p => p.expr === existing)
    } else {
      cronExpr.value = PRESETS[0].expr
      customMode.value = false
    }
  }
})

function pick(p) {
  if (p.expr) {
    cronExpr.value = p.expr
    customMode.value = false
    preview.value = []
  } else {
    customMode.value = true
    preview.value = []
  }
}

async function doPreview() {
  if (!cronExpr.value || previewing.value) return
  previewing.value = true
  error.value = ''
  try {
    preview.value = await previewCron(cronExpr.value.trim())
  } catch (e) {
    preview.value = []
    error.value = e.message || '表达式非法'
  } finally {
    previewing.value = false
  }
}

async function enable() {
  if (!props.task || submitting.value) return
  submitting.value = true
  error.value = ''
  try {
    await setSchedule(props.task.id, { enabled: true, cronExpr: cronExpr.value.trim() })
    emit('saved')
    emit('update:modelValue', false)
  } catch (e) {
    error.value = e.message || '保存失败'
  } finally {
    submitting.value = false
  }
}

async function disable() {
  if (!props.task || submitting.value) return
  submitting.value = true
  error.value = ''
  try {
    await setSchedule(props.task.id, { enabled: false })
    emit('saved')
    emit('update:modelValue', false)
  } catch (e) {
    error.value = e.message || '停用失败'
  } finally {
    submitting.value = false
  }
}

function close() {
  emit('update:modelValue', false)
}
</script>

<style scoped>
.sch-mask {
  position: fixed; inset: 0; z-index: 120;
  background: rgba(23, 23, 23, 0.32); display: flex; align-items: center; justify-content: center;
}
.sch-box {
  width: 460px; max-width: calc(100vw - 32px); max-height: 82vh; overflow-y: auto;
  padding: 24px; background: #fff; border-radius: 16px; box-shadow: var(--shadow-modal);
}
.sch-title { font-size: 16px; font-weight: 600; margin-bottom: 8px; }
.sch-hint { font-size: 12px; color: var(--text-3); line-height: 1.7; margin-bottom: 16px; }
.field-label { display: block; font-size: 12px; font-weight: 600; color: var(--text-2); margin: 12px 0 6px; }
.preset-row { display: flex; gap: 8px; flex-wrap: wrap; }
.preset-btn {
  height: 30px; padding: 0 14px; border-radius: 999px; font: inherit; font-size: 12px; cursor: pointer;
  border: 1px solid var(--border-default); background: #fff; color: var(--text-2);
}
.preset-btn.on {
  color: var(--green-deep); border-color: rgba(23, 160, 94, .5); background: rgba(23, 160, 94, .1);
}
.cron-input {
  width: 100%; height: 36px; padding: 0 12px; font: inherit; font-size: 13px;
  font-family: "SF Mono", Consolas, monospace;
  border: 1px solid var(--border-default); border-radius: 10px; outline: none;
}
.cron-input:focus { border-color: var(--green); }
.preview-row { margin-top: 14px; }
.preview-btn { padding: 4px 12px; font-size: 12px; }
.preview-list { margin-top: 8px; }
.preview-item {
  font-size: 12px; color: var(--text-2); font-family: "SF Mono", Consolas, monospace;
  background: #F3F4F6; border-radius: 8px; padding: 6px 10px; margin-bottom: 4px;
}
.error-tip { margin-top: 12px; font-size: 12px; color: var(--danger); }
.sch-actions { display: flex; align-items: center; gap: 8px; margin-top: 18px; }
</style>

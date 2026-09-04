<template>
  <!-- 任务列表面板：状态肩条 + 步骤/cron 徽标 + 立即执行/定时/历史/删除 -->
  <PanelCard title="我的任务">
    <template #actions>
      <button class="btn refresh-btn" @click="$emit('refresh')">刷新</button>
    </template>
    <div v-if="!tasks.length" class="empty">
      暂无可执行任务<br />在运营工作台对话完成后，点回答下方的「存为任务」即可固化流程
    </div>
    <div v-for="t in tasks" :key="t.id" class="task-card" :class="rowClass(t)">
      <div class="task-head">
        <span class="task-title">{{ t.title }}</span>
        <span v-if="t.status === 'PAUSED'" class="pill warn-pill">已暂停</span>
      </div>
      <div class="task-meta">
        <span class="mono">{{ t.step_count }} 步</span>
        <span class="mono" v-if="t.schedule_enabled">{{ t.cron_expr }}</span>
        <span v-else class="plain">手动</span>
        <span v-if="t.last_status" class="last-status" :class="String(t.last_status).toLowerCase()">
          上次 {{ statusLabel(t.last_status) }}{{ t.last_fire_at ? ' · ' + fmtTime(t.last_fire_at) : '' }}
        </span>
        <span v-else class="plain">未执行过</span>
      </div>
      <div class="task-actions">
        <button class="btn op" :disabled="runningId === t.id" @click="runTask(t)">
          {{ runningId === t.id ? '执行中…' : '立即执行' }}
        </button>
        <button class="btn op" @click="$emit('schedule', t)">定时</button>
        <button class="btn op" @click="$emit('history', t)">历史</button>
        <button class="btn op danger" @click="toDelete = t">删除</button>
      </div>
    </div>

    <div v-if="runError" class="run-error">{{ runError }}</div>
    <ConfirmDialog
      :model-value="!!toDelete"
      title="删除任务"
      :message="toDelete ? '确定归档任务「' + toDelete.title + '」？归档后不可恢复，执行历史保留。' : ''"
      danger
      @confirm="doDelete"
      @cancel="toDelete = null"
    />
  </PanelCard>
</template>

<script setup>
const props = defineProps({
  tasks: { type: Array, default: () => [] }
})
const emit = defineEmits(['refresh', 'run-done', 'schedule', 'history'])

const { run, remove } = useTasks()
const runningId = ref(null)
const runError = ref('')
const toDelete = ref(null)

function rowClass(t) {
  return {
    active: t.status === 'ACTIVE',
    paused: t.status === 'PAUSED'
  }
}

function statusLabel(s) {
  return { SUCCESS: '成功', PARTIAL: '部分成功', FAILED: '失败', SKIPPED: '跳过' }[s] || s || '-'
}

function fmtTime(t) {
  if (!t) return '-'
  const d = new Date(t)
  return isNaN(d.getTime()) ? String(t) : d.toLocaleString('zh-CN', { hour12: false })
}

async function runTask(t) {
  if (runningId.value) return
  runningId.value = t.id
  runError.value = ''
  try {
    const res = await run(t.id)
    emit('run-done', res)
    emit('refresh')
  } catch (e) {
    runError.value = '执行失败：' + (e.message || e)
  } finally {
    runningId.value = null
  }
}

async function doDelete() {
  if (!toDelete.value) return
  try {
    await remove(toDelete.value.id)
    emit('refresh')
  } catch (e) {
    runError.value = '删除失败：' + (e.message || e)
  } finally {
    toDelete.value = null
  }
}
</script>

<style scoped>
.empty { color: var(--text-3); font-size: 12px; text-align: center; padding: 28px 8px; line-height: 2; }
.refresh-btn { padding: 3px 10px; font-size: 12px; }
/* 任务行：状态肩条（ACTIVE 绿 / PAUSED 橙）+ hover 微抬升，视觉语言同 TaskCardList */
.task-card {
  position: relative; overflow: hidden;
  border: 1px solid var(--line); border-radius: 12px; padding: 12px 14px; margin-bottom: 12px;
  background: #fff;
  box-shadow: 0 2px 8px rgba(13, 64, 38, .05);
  transition: border-color .15s, box-shadow .15s, transform .15s;
}
.task-card::before { content: ""; position: absolute; left: 0; top: 0; bottom: 0; width: 3px; background: var(--line); }
.task-card.active::before { background: var(--green); }
.task-card.paused::before { background: var(--orange); }
.task-card:hover {
  border-color: rgba(23, 160, 94, .35);
  box-shadow: 0 8px 20px rgba(13, 64, 38, .10);
  transform: translateY(-1px);
}
.task-head { display: flex; align-items: center; justify-content: space-between; gap: 8px; }
.task-title { font-size: 13.5px; font-weight: 700; overflow: hidden; text-overflow: ellipsis; white-space: nowrap; }
.warn-pill {
  font-size: 11px; padding: 2px 10px; border-radius: 999px; flex-shrink: 0;
  background: rgba(217, 119, 6, .1); color: var(--orange, #D97706);
}
.task-meta {
  display: flex; align-items: center; gap: 8px; flex-wrap: wrap; margin-top: 6px;
  font-size: 11px; color: var(--text-3);
}
.task-meta .mono {
  font-family: "SF Mono", Consolas, monospace;
  background: #fff; border: 1px solid var(--line); border-radius: 6px; padding: 2px 8px;
}
.task-meta .plain { padding: 2px 0; }
.last-status.success { color: var(--green-deep); }
.last-status.partial { color: var(--orange, #D97706); }
.last-status.failed { color: var(--danger); }
.task-actions { margin-top: 8px; display: flex; justify-content: flex-end; gap: 8px; }
/* 绿色 tint 药丸操作钮（同 TaskCardList 操作语言） */
.op {
  padding: 4px 12px; font-size: 12px;
  color: var(--green); border-color: rgba(23, 160, 94, .35); background: rgba(23, 160, 94, .08);
}
.op:hover { color: var(--green-deep); border-color: rgba(23, 160, 94, .5); background: rgba(23, 160, 94, .16); }
.op.danger { color: var(--danger); border-color: rgba(220, 38, 38, .3); background: rgba(220, 38, 38, .05); }
.run-error {
  margin-top: 8px; font-size: 12px; color: var(--danger);
  background: rgba(220, 38, 38, .06); border-radius: 10px; padding: 10px 12px;
}
</style>

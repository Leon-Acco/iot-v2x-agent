<template>
  <!-- 单次执行结果视图：每步一个子块（状态头 + DataTable / 失败原因）；手动执行与历史详情共用 -->
  <div class="run-view">
    <div class="run-head">
      <span class="run-title">{{ run.title || ('任务 #' + run.taskId) }}</span>
      <StatusPill :status="run.status" :label="statusLabel(run.status)" />
      <span v-if="run.elapsedMs != null" class="run-meta">{{ run.elapsedMs }}ms</span>
      <span v-if="run.triggerType" class="run-meta">{{ run.triggerType === 'cron' ? '定时' : '手动' }} · {{ run.triggeredBy }}</span>
      <span v-if="run.startedAt" class="run-meta">{{ fmtTime(run.startedAt) }}</span>
    </div>
    <div v-if="run.error" class="run-error">{{ run.error }}</div>

    <!-- 分析报告：任务执行后由分析模型基于各步结果生成（LLM 失败时无此块，不影响状态）；Markdown 渲染 -->
    <div v-if="run.conclusion" class="report-block">
      <div class="report-label">分析报告</div>
      <div class="report-body md" v-html="conclusionHtml"></div>
    </div>

    <div v-for="s in run.steps || []" :key="s.seq" class="step-block" :class="String(s.status).toLowerCase()">
      <div class="step-head">
        <span class="step-seq">{{ s.seq }}</span>
        <span class="step-name">{{ s.displayName || s.capabilityId }}</span>
        <StatusPill :status="s.status" :label="statusLabel(s.status)" />
        <span v-if="s.result" class="step-meta">{{ s.result.rowCount }} 行</span>
        <span v-if="s.elapsedMs != null" class="step-meta">{{ s.elapsedMs }}ms</span>
      </div>
      <div v-if="s.status === 'SKIPPED' || s.status === 'FAILED'" class="step-error">{{ s.error }}</div>
      <DataTable
        v-if="s.result && Array.isArray(s.result.columns) && s.result.columns.length"
        :columns="s.result.columns"
        :rows="s.result.rows || []"
        :row-count="s.result.rowCount"
        :truncated="!!s.result.truncated"
      />
      <div v-if="s.result && s.result.freshness" class="fresh-note">
        数据截至 {{ s.result.freshness.dataAsOf }}（{{ s.result.freshness.policyType }}，延迟约 {{ s.result.freshness.expectedDelayMin }} 分钟）
      </div>
    </div>
    <div v-if="!(run.steps || []).length" class="empty">本次执行无步骤结果</div>
  </div>
</template>

<script setup>
// props.run = RunResponse：{runId,taskId,title,triggerType,triggeredBy,status,elapsedMs,startedAt,error,conclusion,steps:[{seq,capabilityId,displayName,status,error,elapsedMs,result:{columns,rows,rowCount,truncated,freshness}}]}
const props = defineProps({
  run: { type: Object, required: true }
})

// 分析报告 Markdown 渲染（复用聊天结论的渲染管线，防 XSS / 单换行成 <br>）
const { render: renderMd, stripFallback } = useMarkdown()
const conclusionHtml = ref('')
watch(() => props.run && props.run.conclusion, async v => {
  conclusionHtml.value = v ? (await renderMd(v).catch(() => stripFallback(v))) : ''
}, { immediate: true })

function statusLabel(s) {
  return {
    RUNNING: '执行中', SUCCESS: '成功', PARTIAL: '部分成功', FAILED: '失败', SKIPPED: '跳过'
  }[s] || s || '-'
}

function fmtTime(t) {
  if (!t) return '-'
  const d = new Date(t)
  return isNaN(d.getTime()) ? String(t) : d.toLocaleString('zh-CN', { hour12: false })
}
</script>

<style scoped>
.run-view { display: flex; flex-direction: column; gap: 12px; }
.run-head { display: flex; align-items: center; gap: 10px; flex-wrap: wrap; }
.run-title { font-size: 15px; font-weight: 700; }
.run-meta { font-size: 11px; color: var(--text-3); }
.run-error {
  font-size: 12px; color: var(--danger); background: rgba(220, 38, 38, .06);
  border-radius: 10px; padding: 10px 12px; line-height: 1.7;
}
/* 分析报告块：浅底卡 + 标签 */
.report-block {
  border: 1px solid rgba(23, 160, 94, .25); background: rgba(23, 160, 94, .04);
  border-radius: 12px; padding: 12px 14px;
}
.report-label {
  font-size: 11px; font-weight: 700; color: var(--green-deep);
  letter-spacing: .08em; margin-bottom: 6px;
}
.report-body { font-size: 13px; color: var(--text-1); line-height: 1.9; word-break: break-word; }
/* 报告 Markdown 元素：加粗/列表/表格（TASK_REPORT_PROMPT 要求 Markdown 输出） */
.report-body.md :deep(p) { margin: 0 0 6px; }
.report-body.md :deep(p:last-child) { margin-bottom: 0; }
.report-body.md :deep(strong) { font-weight: 700; }
.report-body.md :deep(ul), .report-body.md :deep(ol) { margin: 4px 0 6px; padding-left: 20px; }
.report-body.md :deep(li) { margin: 2px 0; }
.report-body.md :deep(h1), .report-body.md :deep(h2), .report-body.md :deep(h3) {
  font-size: 13.5px; margin: 8px 0 4px;
}
.report-body.md :deep(code) {
  font-family: "SF Mono", Consolas, monospace; font-size: 12px;
  background: rgba(23, 160, 94, .08); border-radius: 4px; padding: 1px 5px;
}
.step-block {
  position: relative; overflow: hidden;
  border: 1px solid var(--line); border-radius: 12px; padding: 12px 14px; background: #fff;
}
.step-block::before {
  content: ""; position: absolute; left: 0; top: 0; bottom: 0; width: 3px; background: var(--t3, var(--line));
}
.step-block.success::before { background: var(--green); }
.step-block.partial::before { background: var(--orange); }
.step-block.failed::before { background: var(--danger); }
.step-block.skipped::before { background: var(--text-3); }
.step-head { display: flex; align-items: center; gap: 10px; flex-wrap: wrap; margin-bottom: 8px; }
.step-seq {
  width: 18px; height: 18px; border-radius: 50%; flex-shrink: 0;
  background: #F3F4F6; color: var(--text-2); font-size: 11px; font-weight: 700;
  display: inline-flex; align-items: center; justify-content: center;
}
.step-name { font-size: 13px; font-weight: 600; }
.step-meta { font-size: 11px; color: var(--text-3); }
.step-error { font-size: 12px; color: var(--text-2); background: #F3F3F5; border-radius: 8px; padding: 8px 10px; }
.fresh-note { margin-top: 6px; font-size: 11px; color: var(--text-3); }
.empty { color: var(--text-3); font-size: 12px; text-align: center; padding: 16px 8px; }
</style>

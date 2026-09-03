<template>
  <!-- 对话流容器：768px 居中消息列，钉底时自动滚动 -->
  <div ref="scrollEl" class="chat-stream" @scroll="onScroll">
    <div class="chat-column">
      <EmptyState
        v-if="!messages.length"
        title="你好，我是设备运营 Agent"
        desc="可以问我在线率、告警、里程，或直接要求生成图表"
        :chips="sampleQuestions"
        @pick="$emit('ask', $event)"
      />
      <template v-for="msg in messages" :key="msg.id">
        <UserMessage v-if="msg.role === 'user'" :content="msg.content" @edit="$emit('edit', $event)" />
        <AiMessage v-else :stream="msg.stream" @retry="$emit('retry', msg)" @clarify="$emit('clarify', $event)" @taskcard="$emit('taskcard', msg)" />
      </template>
      <div v-if="followUps.length" class="follow-row">
        <button v-for="(c, i) in followUps" :key="i" class="chip" @click="$emit('ask', c)">{{ c }}</button>
      </div>
    </div>
  </div>
</template>

<script setup>
const props = defineProps({
  messages: { type: Array, default: () => [] },
  followUps: { type: Array, default: () => [] },
  sampleQuestions: { type: Array, default: () => [] }
})
defineEmits(['ask', 'edit', 'retry', 'clarify', 'taskcard'])

const scrollEl = ref(null)
let pinned = true

function onScroll() {
  const el = scrollEl.value
  if (!el) return
  pinned = el.scrollHeight - el.scrollTop - el.clientHeight < 60
}

function scrollToBottom() {
  const el = scrollEl.value
  if (el && pinned) el.scrollTop = el.scrollHeight
}

onUpdated(() => {
  requestAnimationFrame(scrollToBottom)
})
</script>

<style scoped>
.chat-stream { flex: 1; overflow-y: auto; min-height: 0; }
.chat-column { max-width: 98%; margin: 0 auto; padding: 24px 16px 16px; }
.follow-row { display: flex; flex-wrap: wrap; gap: 8px; padding: 4px 0 16px; }
.chip {
  border: 1px solid var(--border-default); background: #fff;
  color: var(--text-2); border-radius: 999px; padding: 6px 14px;
  font-size: 12px; cursor: pointer; transition: all .15s ease;
}
.chip:hover { border-color: var(--primary); color: var(--primary); background: var(--primary-light); }
</style>

<template>
  <!-- 会话侧栏：展开=搜索+置顶+按时间分组列表；折叠=60px 窄条（新建 + 最近 3 条缩写） -->
  <aside class="sessions" :class="{ rail: folded }" aria-label="会话列表">
    <!-- 折叠窄条：新建 + 最近 3 条缩写圆点 + 展开 -->
    <template v-if="folded">
      <button class="rail-new" type="button" title="新建会话" @click="$emit('create')">＋</button>
      <div class="rail-list">
        <button
          v-for="s in sessions.slice(0, 3)"
          :key="s.id"
          class="rail-dot"
          :class="{ on: s.id === activeId }"
          type="button"
          :title="titleOf(s)"
          @click="$emit('select', s.id)"
        >{{ abbrOf(s) }}</button>
      </div>
      <button class="rail-expand" type="button" title="展开会话列表" @click="$emit('fold')">»</button>
    </template>

    <!-- 展开态：搜索 + 置顶/分组列表 -->
    <template v-else>
      <div class="sess-head">
        <span class="st">历史会话</span>
        <button class="fold-btn" type="button" :aria-expanded="folded ? 'false' : 'true'" @click="$emit('fold')">收起</button>
      </div>
      <button class="newchat" @click="$emit('create')">＋ 新建会话</button>
      <div class="sess-search">
        <input v-model="keyword" type="text" placeholder="搜索会话…" />
      </div>
      <div class="session-list">
        <div v-if="!sessions.length" class="empty">暂无历史会话</div>
        <div v-else-if="!filtered.length" class="empty">没有匹配的会话</div>
        <template v-for="g in groups" :key="g.label">
          <div class="group-label">{{ g.label }}</div>
          <div
            v-for="s in g.items"
            :key="s.id"
            class="sess"
            :class="{ on: s.id === activeId, pin: pins.includes(s.id) }"
            @click="$emit('select', s.id)"
          >
            <span v-if="pins.includes(s.id)" class="pin-mark" title="已置顶">📌</span>
            <span class="session-title">{{ titleOf(s) }}</span>
            <span class="sess-time">{{ timeOf(s) }}</span>
            <span class="sess-ops" @click.stop>
              <button class="op-btn" type="button" :title="pins.includes(s.id) ? '取消置顶' : '置顶'" @click="togglePin(s)">📌</button>
              <button class="op-btn" type="button" title="重命名" @click="rename(s)">✎</button>
              <button class="op-btn del" type="button" title="删除" @click="$emit('delete', s.id)">✕</button>
            </span>
          </div>
        </template>
      </div>
    </template>
  </aside>
</template>

<script setup>
const props = defineProps({
  sessions: { type: Array, default: () => [] },
  activeId: { type: String, default: '' },
  folded: { type: Boolean, default: false }
})
const emit = defineEmits(['create', 'select', 'fold', 'rename', 'delete'])

// 置顶：localStorage 持久化
const LS_PINS = 'v2x.sess.pins'
const pins = ref([])
try { pins.value = JSON.parse(localStorage.getItem(LS_PINS) || '[]') } catch (e) { pins.value = [] }

function togglePin(s) {
  const i = pins.value.indexOf(s.id)
  if (i >= 0) pins.value.splice(i, 1)
  else pins.value.unshift(s.id)
  try { localStorage.setItem(LS_PINS, JSON.stringify(pins.value)) } catch (e) { /* ignore */ }
}

// 标题兜底：空/「会话」占位改「未命名对话」
function titleOf(s) {
  const t = (s.title || '').trim()
  return !t || t === '会话' ? '未命名对话' : t
}

// 窄条缩写：取标题前 2 字符
function abbrOf(s) {
  return titleOf(s).slice(0, 2)
}

// 时间显示：今天显 HH:mm，更早显 MM-DD
function timeOf(s) {
  const d = new Date(s.createdAt || 0)
  const p = n => String(n).padStart(2, '0')
  const now = new Date()
  const sameDay = d.toDateString() === now.toDateString()
  return sameDay ? p(d.getHours()) + ':' + p(d.getMinutes()) : (d.getMonth() + 1) + '-' + d.getDate()
}

// 搜索过滤
const keyword = ref('')
const filtered = computed(() => {
  const k = keyword.value.trim().toLowerCase()
  if (!k) return props.sessions
  return props.sessions.filter(s => titleOf(s).toLowerCase().includes(k))
})

// 分组：置顶 / 今天 / 本周 / 更早
const groups = computed(() => {
  const now = new Date()
  const startToday = new Date(now.getFullYear(), now.getMonth(), now.getDate()).getTime()
  const weekAgo = startToday - 6 * 86400000
  const pinned = []
  const today = []
  const week = []
  const earlier = []
  for (const s of filtered.value) {
    if (pins.value.includes(s.id)) pinned.push(s)
    else if (s.createdAt >= startToday) today.push(s)
    else if (s.createdAt >= weekAgo) week.push(s)
    else earlier.push(s)
  }
  return [
    { label: '置顶', items: pinned },
    { label: '今天', items: today },
    { label: '本周', items: week },
    { label: '更早', items: earlier }
  ].filter(g => g.items.length)
})

// 行内重命名：确认弹窗输入新标题（同步到后端 /ag-ui/sessions/{id}/rename）
function rename(s) {
  const title = window.prompt('重命名会话', titleOf(s))
  if (title && title.trim()) emit('rename', s.id, title.trim())
}
</script>

<style scoped>
.sessions {
  padding: 12px 10px; display: flex; flex-direction: column; gap: 8px;
  align-self: start; max-height: 100%; min-height: 0;
  background: var(--panel); border: 1px solid var(--line); border-radius: 16px;
  box-shadow: var(--shadow);
}
/* ---- 折叠窄条（60px）：竖排新建 + 最近 3 条缩写 + 展开 ---- */
.sessions.rail { align-items: center; padding: 10px 6px; gap: 6px; }
.rail-new {
  width: 38px; height: 38px; border-radius: 50%; border: 1px dashed var(--green-deep);
  background: none; color: var(--green-ink); font-size: 18px; cursor: pointer; flex: 0 0 auto;
}
.rail-new:hover { background: rgba(23, 160, 94, .1); }
.rail-list { display: flex; flex-direction: column; gap: 6px; flex: 1; min-height: 0; overflow-y: auto; width: 100%; align-items: center; }
.rail-dot {
  width: 34px; height: 34px; border-radius: 50%; border: 1px solid var(--line);
  background: #fff; color: var(--t3); font: inherit; font-size: 11px; cursor: pointer;
  overflow: hidden; white-space: nowrap; flex: 0 0 auto; padding: 0;
}
.rail-dot:hover { border-color: var(--green-deep); color: var(--green-ink); }
.rail-dot.on { background: rgba(23, 160, 94, .14); border-color: var(--green-deep); color: var(--green-ink); font-weight: 700; }
.rail-expand {
  width: 34px; height: 26px; border-radius: 999px; border: none;
  background: rgba(23, 160, 94, .1); color: var(--green-ink); cursor: pointer; flex: 0 0 auto;
}
.rail-expand:hover { background: rgba(23, 160, 94, .2); }
/* ---- 展开态 ---- */
.sess-head { display: flex; align-items: center; justify-content: space-between; gap: 8px; padding: 2px 4px 0; }
.sess-head .st { font-size: 11.5px; font-weight: 700; color: var(--t2); letter-spacing: .06em; }
.fold-btn {
  height: 26px; padding: 0 10px; border-radius: 999px; border: 1px solid var(--line-strong);
  background: #fff; color: var(--t3); font: inherit; font-size: 11px; cursor: pointer;
  white-space: nowrap; flex: 0 0 auto;
}
.fold-btn:hover { color: var(--green-ink); border-color: rgba(23, 160, 94, .5); }
.newchat {
  height: 38px; border: 1px dashed var(--green-deep); border-radius: 999px; background: none;
  color: var(--green-ink); font: inherit; font-size: 13px; font-weight: 600; cursor: pointer; flex: 0 0 auto;
}
.newchat:hover { background: rgba(23, 160, 94, .07); }
.sess-search { flex: 0 0 auto; }
.sess-search input {
  width: 100%; height: 28px; border-radius: 999px; border: 1px solid var(--line);
  background: var(--field); color: var(--ink); font: inherit; font-size: 12px;
  padding: 0 12px; outline: none;
}
.sess-search input:focus { border-color: var(--green-deep); }
.session-list { flex: 1; overflow-y: auto; min-height: 0; display: flex; flex-direction: column; gap: 2px; }
.empty { text-align: center; color: var(--t3); font-size: 12px; padding: 24px 0; }
.group-label {
  font-size: 10.5px; color: var(--t3); letter-spacing: 1px; margin: 8px 4px 3px;
}
.group-label:first-child { margin-top: 0; }
.sess {
  padding: 7px 12px; border-radius: 999px; cursor: pointer;
  overflow: hidden; text-overflow: ellipsis; white-space: nowrap;
  font-size: 12.5px; color: var(--t2);
  display: flex; align-items: center; gap: 5px;
}
.sess:hover { background: var(--field); color: var(--ink); }
.sess.on { background: rgba(23, 160, 94, .12); color: var(--green-ink); font-weight: 600; }
.pin-mark { font-size: 10px; flex: 0 0 auto; }
.session-title { flex: 1; min-width: 0; overflow: hidden; text-overflow: ellipsis; white-space: nowrap; }
.sess-time { font-size: 10.5px; color: var(--t3); flex: 0 0 auto; font-variant-numeric: tabular-nums; }
.sess-ops { display: none; margin-left: auto; flex: 0 0 auto; }
.sess:hover .sess-ops { display: inline-flex; gap: 2px; }
.sess:hover .sess-time { display: none; }
.op-btn {
  width: 20px; height: 20px; border: none; border-radius: 6px;
  background: transparent; color: var(--t3); font-size: 11px; cursor: pointer;
  display: inline-flex; align-items: center; justify-content: center; padding: 0;
}
.op-btn:hover { background: rgba(23, 160, 94, .14); color: var(--green-ink); }
.op-btn.del:hover { background: rgba(220, 38, 38, .12); color: #dc2626; }

/* ≤1180：窄屏会话栏收窄 */
@media (max-width: 1180px) {
  .sessions:not(.rail) { padding: 10px 8px; }
  .sess-time { display: none; }
}
</style>

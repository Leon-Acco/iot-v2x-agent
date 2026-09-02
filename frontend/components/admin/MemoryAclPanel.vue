<template>
  <!-- P6 记忆库 + ACL：搜索 / 展开编辑四类 token / 申请删除 -->
  <div>
    <div class="acl-toolbar">
      <input v-model.trim="keyword" class="kw-input" placeholder="搜索记忆内容，回车检索" @keydown.enter="load" />
      <button class="btn" @click="load">搜索</button>
    </div>

    <div v-if="!items.length" class="empty">暂无记忆条目</div>
    <div v-for="m in items" :key="m.id" class="mem-card">
      <div class="m-content">{{ m.content }}</div>
      <div class="m-meta">
        <span class="pill">{{ m.scope_type }}<template v-if="m.scope_key">:{{ m.scope_key }}</template></span>
        <StatusPill :status="m.status" :label="m.status === 'ARCHIVED' ? '已归档' : '有效'" />
        <span>失效时间：{{ fmt(m.expire_at) }}</span>
        <span class="ops">
          <button class="btn op" @click="toggle(m.id)">{{ openId === m.id ? '收起 ACL' : 'ACL' }}</button>
          <button v-if="m.status === 'ACTIVE'" class="btn op danger" @click="askRemoval(m)">申请删除</button>
        </span>
      </div>
      <div v-if="openId === m.id" class="acl-box">
        <div class="acl-title">当前 ACL（user:/role:/fleet:/global:）</div>
        <div class="acl-tokens">
          <span v-for="t in aclTokens" :key="t.id" class="acl-chip">
            {{ t.principal_token }}
            <button class="chip-x" @click="removeToken(t)">✕</button>
          </span>
          <span v-if="!aclTokens.length" class="empty-inline">无</span>
        </div>
        <div class="acl-add">
          <input v-model.trim="newToken" placeholder="如 fleet:F001 或 role:operator" @keydown.enter="addToken(m.id)" />
          <button class="btn btn-primary" @click="addToken(m.id)">添加</button>
        </div>
        <div v-if="aclError" class="err">{{ aclError }}</div>
      </div>
    </div>
  </div>
</template>

<script setup>
const api = useApi()
const items = ref([])
const keyword = ref('')
const openId = ref(null)
const aclTokens = ref([])
const newToken = ref('')
const aclError = ref('')

async function load() {
  const data = await api.get('/admin/memory/items' + (keyword.value ? '?keyword=' + encodeURIComponent(keyword.value) : ''))
  items.value = Array.isArray(data) ? data : []
}

async function toggle(id) {
  if (openId.value === id) { openId.value = null; return }
  openId.value = id
  aclError.value = ''
  newToken.value = ''
  const data = await api.get('/admin/memory/items/' + id + '/acl')
  aclTokens.value = Array.isArray(data) ? data : []
}

async function addToken(id) {
  if (!newToken.value) return
  aclError.value = ''
  try {
    await api.post('/admin/memory/items/' + id + '/acl', { principalToken: newToken.value })
    newToken.value = ''
    const data = await api.get('/admin/memory/items/' + id + '/acl')
    aclTokens.value = Array.isArray(data) ? data : []
  } catch (e) { aclError.value = e.message }
}

async function removeToken(t) {
  if (!openId.value) return
  try {
    await api.del('/admin/memory/items/' + openId.value + '/acl?token=' + encodeURIComponent(t.principal_token))
    aclTokens.value = aclTokens.value.filter(x => x.id !== t.id)
  } catch (e) { aclError.value = e.message }
}

async function askRemoval(m) {
  const reason = window.prompt('删除理由（必填，将进入申请流程）')
  if (!reason) return
  try {
    await api.post('/admin/memory/items/' + m.id + '/removal-requests', { reason })
    alert('已提交删除申请')
  } catch (e) { alert(e.message) }
}

function fmt(t) {
  if (!t) return '-'
  const d = new Date(t)
  return isNaN(d.getTime()) ? String(t) : d.toLocaleDateString('zh-CN')
}

onMounted(load)
defineExpose({ reload: load })
</script>

<style scoped>
.acl-toolbar { display: flex; gap: 8px; margin-bottom: 14px; }
.kw-input {
  width: 280px; padding: 8px 12px; font-size: 13px;
  border: 1px solid #E5E5E5; border-radius: 8px; outline: none; background: #F3F3F5;
}
.empty { text-align: center; color: var(--text-3); padding: 40px 0; }
.mem-card {
  border: 1px solid #F0F0F0; border-radius: 12px; padding: 12px 14px; margin-bottom: 10px;
  background: #FFFFFF;
}
.m-content { font-size: 13px; line-height: 1.7; }
.m-meta {
  display: flex; gap: 10px; align-items: center; flex-wrap: wrap;
  margin-top: 8px; font-size: 11px; color: var(--text-3);
}
.pill { background: #F3F4F6; border-radius: 12px; padding: 2px 8px; }
.ops { margin-left: auto; display: flex; gap: 4px; }
.op { padding: 3px 10px; font-size: 12px; }
.op.danger { color: var(--danger); }
.acl-box { margin-top: 10px; border-top: 1px dashed #E5E5E5; padding-top: 10px; }
.acl-title { font-size: 12px; font-weight: 700; color: var(--text-2); margin-bottom: 8px; }
.acl-tokens { display: flex; flex-wrap: wrap; gap: 6px; }
.acl-chip {
  display: inline-flex; align-items: center; gap: 4px;
  background: var(--primary-light); color: var(--primary);
  border-radius: 12px; padding: 3px 6px 3px 10px; font-size: 12px;
  font-family: "SF Mono", Consolas, monospace;
}
.chip-x { border: none; background: transparent; color: var(--text-3); cursor: pointer; font-size: 11px; }
.chip-x:hover { color: var(--danger); }
.empty-inline { color: var(--text-3); font-size: 12px; }
.acl-add { display: flex; gap: 8px; margin-top: 10px; }
.acl-add input {
  flex: 1; padding: 7px 10px; font-size: 12px;
  border: 1px solid #E5E5E5; border-radius: 8px; outline: none; background: #fff;
}
.acl-add .btn { padding: 6px 14px; font-size: 12px; }
.err { color: var(--danger); font-size: 12px; margin-top: 6px; }
</style>

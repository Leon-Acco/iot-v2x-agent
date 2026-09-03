<template>
  <!-- v3a 侧栏底部用户区 -->
  <div class="user-area">
    <div class="avatar">{{ initial }}</div>
    <div class="info">
      <div class="name">{{ user?.username || '未登录' }}</div>
    </div>
    <button class="logout" :title="logoutTitle" @click="$emit('logout')">⏏</button>
  </div>
</template>

<script setup>
const props = defineProps({
  user: { type: Object, default: null },
  logoutTitle: { type: String, default: '退出登录' }
})
defineEmits(['logout'])
const initial = computed(() => (props.user?.username || 'U').slice(0, 1).toUpperCase())
const fleetText = computed(() => {
  const fleets = props.user?.fleetIds
  if (props.user?.admin) return 'admin'
  if (fleets && fleets.length) return fleets.join(' / ')
  return ''
})
</script>

<style scoped>
.user-area {
  display: flex; align-items: center; gap: 10px; margin: 0 12px;
  padding: 12px 6px 0;
  border-top: 1px solid var(--chrome-line);
}
.avatar {
  width: 34px; height: 34px; border-radius: 50%; flex-shrink: 0;
  display: grid; place-items: center; font-weight: 600; font-size: 13px;
  background: rgba(23, 160, 94, .12); color: var(--green);
  border: 1px solid rgba(23, 160, 94, .3);
}
.info { flex: 1; min-width: 0; }
.name {
  font-size: 13px; font-weight: 600; color: var(--chrome-txt);
  overflow: hidden; text-overflow: ellipsis; white-space: nowrap;
}
.logout {
  width: 30px; height: 30px; border: 1px solid var(--chrome-line); border-radius: 8px;
  background: none; color: var(--chrome-t3); cursor: pointer; font-size: 13px;
  transition: color .15s, border-color .15s;
}
.logout:hover { color: var(--orange); border-color: rgba(232, 122, 30, .4); }

@media (max-width: 1020px) {
  .name { display: none; }
}
@media (max-width: 760px) {
  .user-area { display: none; }
}
</style>

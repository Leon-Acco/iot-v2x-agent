<template>
  <!-- teal 侧栏底部用户区 -->
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
  display: flex; align-items: center; gap: 10px;
  padding: 12px 8px 0;
  border-top: 1px solid rgba(255, 255, 255, 0.15);
}
.avatar {
  width: 34px; height: 34px; border-radius: 50%; flex-shrink: 0;
  background: rgba(255, 255, 255, 0.15); color: #fff;
  display: flex; align-items: center; justify-content: center;
  font-weight: 600; font-size: 13px;
}
.info { flex: 1; min-width: 0; }
.name {
  font-size: 13px; font-weight: 500; color: #fff;
  overflow: hidden; text-overflow: ellipsis; white-space: nowrap;
}
.fleet { font-size: 11px; color: rgba(255, 255, 255, 0.6); }
.logout {
  border: none; background: rgba(255, 255, 255, 0.10); color: rgba(255, 255, 255, 0.75);
  font-size: 14px; width: 30px; height: 30px; border-radius: 50%;
  cursor: pointer; display: flex; align-items: center; justify-content: center;
  transition: background .15s, color .15s;
}
.logout:hover { background: rgba(220, 38, 38, 0.55); color: #fff; }
</style>

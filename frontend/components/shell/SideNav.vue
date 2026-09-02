<template>
  <!-- teal 侧栏导航：白色文字，active = 毛玻璃药丸 + 反向圆角缩进内容卡 -->
  <nav class="side-nav">
    <div v-for="group in visibleGroups" :key="group.title" class="nav-group">
      <div class="group-title">{{ group.title }}</div>
      <NuxtLink
        v-for="item in group.items"
        :key="item.path"
        :to="item.path"
        class="nav-item"
        :class="{ active: isActive(item) }"
      >
        <span class="nav-icon">{{ item.icon }}</span>
        <span class="nav-label">{{ item.label }}</span>
      </NuxtLink>
    </div>
  </nav>
</template>

<script setup>
const props = defineProps({
  isAdmin: { type: Boolean, default: false }
})
const route = useRoute()

const navGroups = [
  {
    title: '基础',
    items: [
      { path: '/map', label: '设备孪生地图', icon: '◉' },
      { path: '/chat', label: '运营工作台', icon: '□' }
    ]
  },
  {
    title: '运营',
    items: [
      { path: '/anomaly', label: '异常与任务卡', icon: '⚠' }
    ]
  },
  {
    title: '数据',
    items: [
      { path: '/console/stats', label: '数据统计', icon: '▦' },
      { path: '/console/stats?tab=audit', label: '调用审计', icon: '≡' },
      { path: '/console/memory', label: '记忆治理', icon: '◈' }
    ]
  },
  {
    title: '系统',
    adminOnly: true,
    items: [
      { path: '/console/capabilities', label: 'Capability 管理', icon: '⚙' },
      { path: '/console/a2a', label: 'A2A 授权审批', icon: '⚔' }
    ]
  }
]

const visibleGroups = computed(() =>
  navGroups.filter(g => !g.adminOnly || props.isAdmin)
)

function isActive(item) {
  return route.path === item.path
}
</script>

<style scoped>
.side-nav {
  flex: 1; display: flex; flex-direction: column; gap: 18px;
  overflow-y: auto;
  /* 为反向圆角伪元素让出空间，防裁剪 */
  padding: 14px 12px 14px 0;
  margin: -14px -12px -14px 0;
}
.group-title {
  font-size: 10.5px; font-weight: 600; letter-spacing: .14em;
  color: rgba(255, 255, 255, 0.45);
  padding: 0 12px 6px;
}
.nav-item {
  display: flex; align-items: center;
  height: 52px; padding: 0 16px;
  border-radius: 14px; cursor: pointer;
  color: rgba(255, 255, 255, 0.75);
  transition: all .2s ease;
  font-size: 13px;
}
.nav-item:hover { background: rgba(255, 255, 255, 0.10); color: #fff; }
.nav-item.active {
  position: relative;
  background: var(--glass);
  color: var(--primary-deep);
  border-radius: 14px 0 0 14px;
  margin-right: -12px;
  padding-right: 28px;
  font-weight: 500;
}
/* 内凹弧形缺口：与内容卡同色方块 + 径向 mask 挖出四分之一圆 */
.nav-item.active::before,
.nav-item.active::after {
  content: ''; position: absolute; right: 0;
  width: 14px; height: 14px;
  background: var(--glass);
  pointer-events: none;
}
.nav-item.active::before {
  top: -14px;
  -webkit-mask: radial-gradient(circle 14px at 0 0, transparent 14px, #000 15px);
          mask: radial-gradient(circle 14px at 0 0, transparent 14px, #000 15px);
}
.nav-item.active::after {
  bottom: -14px;
  -webkit-mask: radial-gradient(circle 14px at 0 100%, transparent 14px, #000 15px);
          mask: radial-gradient(circle 14px at 0 100%, transparent 14px, #000 15px);
}
.nav-icon {
  width: 20px; margin-right: 12px; flex-shrink: 0;
  display: flex; align-items: center; justify-content: center;
  font-size: 16px; color: inherit;
}
.nav-label { flex: 1; letter-spacing: .02em; }
</style>

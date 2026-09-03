<template>
  <!-- v3a 分组导航：内联 SVG currentColor 图标，active = 左侧绿条 + 绿tint底 -->
  <nav class="side-nav">
    <div class="nav-scroll">
      <template v-for="group in visibleGroups" :key="group.title">
        <div class="group-title">{{ group.title }}</div>
        <NuxtLink
          v-for="item in group.items"
          :key="item.path"
          :to="item.path"
          class="nav-item"
          :class="{ on: isActive(item) }"
        >
          <span class="ni" aria-hidden="true">
            <svg viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="1.8" stroke-linecap="round" stroke-linejoin="round" v-html="ICONS[item.icon]"></svg>
          </span>
          <span class="nl">{{ item.label }}</span>
        </NuxtLink>
      </template>
    </div>
  </nav>
</template>

<script setup>
const props = defineProps({
  isAdmin: { type: Boolean, default: false }
})
const route = useRoute()

// 图标路径照抄 app-preview-h3-v3a-cockpit.html（currentColor 描边）
const ICONS = {
  grid: '<rect x="4" y="4" width="7" height="7" rx="1.5"/><rect x="13" y="4" width="7" height="7" rx="1.5"/><rect x="4" y="13" width="7" height="7" rx="1.5"/><rect x="13" y="13" width="7" height="7" rx="1.5"/>',
  warn: '<path d="M12 4 2.8 19.5h18.4z"/><path d="M12 10v4"/><path d="M12 17.2h.01"/>',
  chart: '<path d="M5 20v-6"/><path d="M11 20V9"/><path d="M17 20v-9"/><path d="M3 20h18"/>',
  lines: '<path d="M4 6h16"/><path d="M4 12h16"/><path d="M4 18h9"/>',
  db: '<ellipse cx="12" cy="5.5" rx="7.5" ry="2.7"/><path d="M4.5 5.5v6.4c0 1.5 3.4 2.7 7.5 2.7s7.5-1.2 7.5-2.7V5.5"/><path d="M4.5 11.9v6.6c0 1.5 3.4 2.7 7.5 2.7s7.5-1.2 7.5-2.7v-6.6"/>',
  gear: '<circle cx="12" cy="12" r="3.2"/><path d="M12 2.8v2.6M12 18.6v2.6M2.8 12h2.6M18.6 12h2.6M5.5 5.5l1.9 1.9M16.6 16.6l1.9 1.9M18.5 5.5l-1.9 1.9M7.4 16.6l-1.9 1.9"/>',
  shield: '<path d="M12 3l7.5 3v5.4c0 4.6-3.2 8.1-7.5 9.6-4.3-1.5-7.5-5-7.5-9.6V6z"/><path d="M8.8 11.8l2.3 2.3 4.3-4.3"/>'
}

const navGroups = [
  {
    title: '基础',
    items: [
      // 设备孪生地图暂缓开放，入口屏蔽（页面 /map 保留，恢复时取消注释即可）
      // { path: '/map', label: '设备孪生地图', icon: '◉' },
      { path: '/chat', label: '运营工作台', icon: 'grid' }
    ]
  },
  {
    title: '运营',
    items: [
      { path: '/anomaly', label: '异常与任务卡', icon: 'warn' }
    ]
  },
  {
    title: '数据',
    items: [
      { path: '/console/stats', label: '数据统计', icon: 'chart' },
      { path: '/console/stats?tab=audit', label: '调用审计', icon: 'lines' },
      { path: '/console/memory', label: '记忆治理', icon: 'db' }
    ]
  },
  {
    title: '系统',
    adminOnly: true,
    items: [
      { path: '/console/capabilities', label: 'Capability 管理', icon: 'gear' },
      { path: '/console/a2a', label: 'A2A 授权审批', icon: 'shield' }
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
.side-nav { flex: 1; min-height: 0; display: flex; flex-direction: column; }
.nav-scroll { flex: 1; overflow-y: auto; }
.group-title {
  font-size: 10px; font-weight: 600; letter-spacing: .16em;
  color: var(--chrome-t3); padding: 14px 18px 6px;
}
.nav-item {
  display: flex; align-items: center; height: 40px; padding: 0 18px;
  color: var(--chrome-t2); text-decoration: none; font-size: 13px;
  border-left: 2px solid transparent;
}
.nav-item:hover { color: var(--chrome-txt); background: rgba(23, 160, 94, .06); }
.nav-item.on {
  color: var(--green); background: rgba(23, 160, 94, .1);
  border-left-color: var(--green); font-weight: 600;
}
.nav-item .ni {
  width: 18px; height: 18px; margin-right: 10px; flex: 0 0 auto;
  display: inline-flex; align-items: center; justify-content: center;
}
.nav-item .ni svg { width: 16px; height: 16px; display: block; }
.nav-item .nl { letter-spacing: .02em; }

@media (max-width: 1020px) {
  .group-title { display: none; }
  .nav-item {
    justify-content: center; padding: 0; height: 44px;
    border-left: 0; border-right: 2px solid transparent;
  }
  .nav-item.on { border-right-color: var(--green); }
  .nav-item .ni { margin: 0; }
}
@media (max-width: 760px) {
  .side-nav, .nav-scroll { display: contents; }
  .nav-item {
    flex: 0 0 auto; height: 46px; padding: 0 12px;
    border: 0; border-radius: 10px; font-size: 12px; gap: 6px;
    display: inline-flex; align-items: center;
  }
  .nav-item .ni { margin: 0; }
  .nav-item .nl { display: inline; }
  .nav-item.on { background: rgba(23, 160, 94, .14); }
}
</style>

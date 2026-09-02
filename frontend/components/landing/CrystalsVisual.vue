<template>
  <!-- frosted crystals data-space: 5 ice crystals + two-line node labels -->
  <div ref="box" class="crystals-box" :aria-label="lbl">
    <span v-for="(n, i) in NODES" :key="i" class="node-label" :style="tagStyle(i)">
      <span class="nl-icon" v-html="n.icon"></span>
      <span class="nl-text">
        <b>{{ n.title }}</b>
        <i>{{ n.sub }}</i>
      </span>
    </span>
  </div>
</template>

<script setup lang="ts">
import type { CrystalsHost } from "~/webgl/host";

const lbl = "冰晶数字孪生可视化";
const box = ref<HTMLElement | null>(null);
let host: CrystalsHost | null = null;

// node identities: icon + EN title + CN subtitle (matches the crystal interiors)
const ICONS = {
  chip: '<svg viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="1.7"><rect x="7" y="7" width="10" height="10" rx="1"/><rect x="10.5" y="10.5" width="3" height="3"/><path d="M12 2v3M12 19v3M2 12h3M19 12h3"/></svg>',
  db: '<svg viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="1.7"><ellipse cx="12" cy="6" rx="8" ry="3"/><path d="M4 6v12c0 1.7 3.6 3 8 3s8-1.3 8-3V6"/><path d="M4 12c0 1.7 3.6 3 8 3s8-1.3 8-3"/></svg>',
  wifi: '<svg viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="1.7"><path d="M5 12.55a11 11 0 0 1 14.08 0M8.53 16.11a6 6 0 0 1 6.95 0"/><circle cx="12" cy="19.4" r="1.2"/></svg>',
  truck: '<svg viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="1.7"><path d="M2 7h11v9H2z"/><path d="M13 10h4l3 3v3h-7v-6z"/><circle cx="6" cy="18" r="1.6"/><circle cx="16.5" cy="18" r="1.6"/></svg>',
  spark: '<svg viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="1.7"><path d="M12 3l1.9 5.1L19 10l-5.1 1.9L12 17l-1.9-5.1L5 10l5.1-1.9L12 3z"/></svg>',
};
const NODES = [
  { title: "AI CORE", sub: "核心智能中枢", icon: ICONS.chip },
  { title: "DATA", sub: "数据管理", icon: ICONS.db },
  { title: "CONNECT", sub: "连接服务", icon: ICONS.wifi },
  { title: "VEHICLE", sub: "车辆连接", icon: ICONS.truck },
  { title: "INTELLIGENCE", sub: "智能分析", icon: ICONS.spark },
];
const uvById: Record<string, { x: number; y: number }> = {};
// label positions freeze on the first report (no floating jitter)
const frozenUv: Record<string, { x: number; y: number }> = {};
const uvTick = ref(0);
let lastUvTickAt = 0;

// label placement: main below-center, satellites beside each crystal
const TAG_OFFSETS = [170, 66, 72, 60, 66];
const TAG_ALIGN = [0, 0, 1, 0, 1];
const tagStyle = (i: number) => {
  void uvTick.value;
  const uv = frozenUv["crystal" + i];
  if (!uv || !box.value) return { display: "none" };
  const rect = box.value.getBoundingClientRect();
  const x = uv.x * rect.width + (TAG_ALIGN[i] ? 60 : -38);
  return {
    left: x + "px",
    top: uv.y * rect.height + (TAG_OFFSETS[i] || 60) + "px",
  };
};

onMounted(async () => {
  if (!box.value) return;
  try {
    const mod = await import("~/webgl/host");
    host = new mod.CrystalsHost(box.value);
    (window as any).__host = host;
    host.on(mod.CrystalsHost.crystalViewportEvent, ({ id, position }: any) => {
      uvById[id] = position;
      if (!(id in frozenUv)) {
        frozenUv[id] = position;
        uvTick.value++;
      }
    });
    await host.load();
  } catch (e) {
    console.error("crystals mount failed", e);
  }
});

onBeforeUnmount(() => {
  if (host) host.destroy();
  host = null;
});
</script>

<style scoped>
.crystals-box { position: absolute; inset: 0; overflow: hidden; pointer-events: none; }
.crystals-box :deep(canvas) { width: 100% !important; height: 100% !important; display: block; }
.node-label {
  position: absolute; z-index: 5; display: flex; align-items: center; gap: 8px;
  pointer-events: none; white-space: nowrap;
}
.nl-icon { width: 21px; height: 21px; color: #5FC3A5; display: grid; place-items: center; opacity: .85; }
.nl-icon :deep(svg) { width: 21px; height: 21px; }
.nl-text { display: flex; flex-direction: column; line-height: 1.3; }
.nl-text b {
  font-size: 14px; letter-spacing: 2px; color: #7FD4BC; font-weight: 700;
  text-shadow: 0 1px 8px rgba(2, 10, 8, .8);
}
.nl-text i {
  font-style: normal; font-size: 12.5px; color: rgba(190, 218, 208, .78); letter-spacing: .5px;
  text-shadow: 0 1px 6px rgba(255, 255, 255, .8);
}
</style>

// feature icons for crystal interiors, extracted from login.vue features
import { CanvasTexture, SRGBColorSpace } from "three";

interface IconDef { paths: string[]; circles: [number, number, number][] }
export const FEATURES: { title: string; desc: string }[] = [
  { title: "实时监控", desc: "车辆状态实时掌控" },
  { title: "数据驱动", desc: "多维数据智能分析" },
  { title: "AI 智能", desc: "人工智能辅助决策" },
  { title: "安全可靠", desc: "企业级安全防护" },
  { title: "远程诊断", desc: "故障码远程读取, 提前预警车辆故障" },
  { title: "能耗管理", desc: "电耗统计 与 碳减排分析" },
  { title: "全域覆盖", desc: "全国省市车辆分布, 一图总览" },
];

const ICONS: IconDef[] = [
  { paths: ["M12 2v3m0 14v3M2 12h3m14 0h3"], circles: [[12, 12, 7], [12, 12, 2]] },
  { paths: ["M4 20v-7h4v7m2 0V8h4v12m2 0V4h4v16", "m4 9 5-4 4 2 7-5"], circles: [] },
  { paths: ["M12 2v2m0 16v2M4.9 4.9l1.4 1.4m11.4 11.4 1.4 1.4M2 12h2m16 0h2"], circles: [[12, 12, 4]] },
  { paths: ["M12 3 5 6v5c0 4.6 2.9 8 7 10 4.1-2 7-5.4 7-10V6l-7-3Z", "m9 12 2 2 4-4"], circles: [] },
  { paths: ["M14.7 6.3a4 4 0 0 0-5.4 5.4L4 17l3 3 5.3-5.3a4 4 0 0 0 5.4-5.4l-2.5 2.5-2-2 2.5-2.5z"], circles: [] },
  { paths: ["M13 2 4 14h6l-1 8 9-12h-6l1-8z"], circles: [] },
  { paths: ["M3 12h18M12 3c3 3.5 3 14 0 18M12 3c-3 3.5-3 14 0 18"], circles: [[12, 12, 9]] },
];

// draw an icon onto a 256px canvas -> texture (bold glowing white strokes)
export function makeIconTexture(index: number) {
  const def = ICONS[index % ICONS.length];
  const c = document.createElement("canvas");
  c.width = c.height = 256;
  const ctx = c.getContext("2d")!;
  ctx.clearRect(0, 0, 256, 256);
  const sc = (256 / 24) * 0.74;
  const off = (256 - 24 * sc) / 2;
  ctx.translate(off, off);
  ctx.scale(sc, sc);
  ctx.lineCap = "round";
  ctx.lineJoin = "round";
  const strokeAll = () => {
    for (const d of def.paths) ctx.stroke(new Path2D(d));
    for (const [cx, cy, r] of def.circles) {
      ctx.beginPath();
      ctx.arc(cx, cy, r, 0, Math.PI * 2);
      ctx.stroke();
    }
  };
  // pass 1: thick black outline (reads against the bright crystal core)
  ctx.strokeStyle = "rgba(8, 12, 16, 0.95)";
  ctx.lineWidth = 3.8;
  strokeAll();
  // pass 2: white core with a soft glow
  ctx.strokeStyle = "rgba(255, 255, 255, 0.98)";
  ctx.lineWidth = 2.0;
  ctx.shadowColor = "rgba(170, 255, 225, 0.9)";
  ctx.shadowBlur = 12;
  strokeAll();
  strokeAll();
  const tex = new CanvasTexture(c);
  tex.colorSpace = SRGBColorSpace;
  tex.anisotropy = 4;
  return tex;
}

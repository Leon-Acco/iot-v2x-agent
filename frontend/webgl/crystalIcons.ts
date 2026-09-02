// node icons for crystal interiors: AI chip / database / link / truck / sparkle.
// white core with a black outline (double-pass stroke), matching the platform theme.
import { CanvasTexture, SRGBColorSpace } from "three";

interface IconDef { paths: string[]; circles: [number, number, number][] }

const ICONS: IconDef[] = [
  // AI CORE: cpu chip
  {
    paths: [
      "M7 7h10v10H7z",
      "M10.5 10.5h3v3h-3z",
      "M12 2v3M12 19v3M2 12h3M19 12h3",
      "M5.5 5.5l1.5 1.5M17 17l1.5 1.5M18.5 5.5L17 7M7 17l-1.5 1.5",
    ],
    circles: [],
  },
  // DATA: database
  {
    paths: [
      "M12 3c4.97 0 9 1.34 9 3s-4.03 3-9 3-9-1.34-9-3 4.03-3 9-3z",
      "M3 6v12c0 1.66 4.03 3 9 3s9-1.34 9-3V6",
      "M3 12c0 1.66 4.03 3 9 3s9-1.34 9-3",
    ],
    circles: [],
  },
  // CONNECT: wifi / link waves
  {
    paths: [
      "M5 12.55a11 11 0 0 1 14.08 0",
      "M8.53 16.11a6 6 0 0 1 6.95 0",
    ],
    circles: [[12, 19.4, 1.3]],
  },
  // VEHICLE: logistics truck
  {
    paths: [
      "M2 7h11v9H2z",
      "M13 10h4l3 3v3h-7v-6z",
    ],
    circles: [[6, 18, 1.6], [16.5, 18, 1.6]],
  },
  // INTELLIGENCE: sparkle
  {
    paths: [
      "M12 3l1.9 5.1L19 10l-5.1 1.9L12 17l-1.9-5.1L5 10l5.1-1.9L12 3z",
      "M19 15l.9 2.1L22 18l-2.1.9L19 21l-.9-2.1L16 18l2.1-.9L19 15z",
    ],
    circles: [],
  },
];

// draw an icon onto a 256px canvas -> texture (black edge + glowing white core)
export function makeNodeIconTexture(index: number) {
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
  // pass 1: thick black outline
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

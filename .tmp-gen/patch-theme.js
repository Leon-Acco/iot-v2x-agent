// Patch SplatTruck.vue: add light theme (transparent canvas, normal blend, deeper teal fx)
const fs = require('fs');
const f = 'D:/code/work-space-agent/dst-v2x-agent/frontend/components/login/SplatTruck.vue';
let s = fs.readFileSync(f, 'utf8');
const repl = [
  [
    '  autoSpin: { type: Boolean, default: true }',
    "  autoSpin: { type: Boolean, default: true },\n  theme: { type: String, default: 'dark' }   // light: transparent canvas + deeper teal fx"
  ],
  [
    "  gl = canvas.getContext('webgl', { alpha: false, antialias: false, powerPreference: 'high-performance' })",
    "  gl = canvas.getContext('webgl', { alpha: true, antialias: false, powerPreference: 'high-performance' })"
  ],
  [
    '  gl.clearColor(0.008, 0.075, 0.086, 1.0)   // deep tech background',
    "  if (props.theme === 'light') gl.clearColor(0, 0, 0, 0)   // transparent over light page background\n  else gl.clearColor(0.008, 0.075, 0.086, 1.0)   // deep tech background"
  ],
  [
    '  // image layer: normal alpha blend; fx layer: additive glow',
    '  // image layer: normal alpha blend; fx layer: additive glow (dark theme only)'
  ],
  [
    '  gl.blendFunc(gl.SRC_ALPHA, gl.ONE)\n  if (fxBufs) { bindVBO(fxBufs); gl.drawArrays(gl.POINTS, 0, fxCount) }',
    "  if (props.theme !== 'light') gl.blendFunc(gl.SRC_ALPHA, gl.ONE)\n  if (fxBufs) { bindVBO(fxBufs); gl.drawArrays(gl.POINTS, 0, fxCount) }"
  ],
  [
    'function buildFx() {',
    "function buildFx() {\n  const lightFx = props.theme === 'light'\n  const C_GROUND = lightFx ? [0.10, 0.55, 0.50] : [0.30, 0.95, 0.88]\n  const C_ORBIT = lightFx ? [0.12, 0.60, 0.55] : [0.42, 0.98, 0.92]\n  const C_DUST = lightFx ? [0.16, 0.58, 0.54] : [0.45, 0.95, 0.90]"
  ],
  [
    '      0.30, 0.95, 0.88, inner ? 0.34 : 0.5,',
    '      C_GROUND[0], C_GROUND[1], C_GROUND[2], inner ? 0.34 : 0.5,'
  ],
  [
    '      0.42, 0.98, 0.92, 0.42,',
    '      C_ORBIT[0], C_ORBIT[1], C_ORBIT[2], 0.42,'
  ],
  [
    '      0.45, 0.95, 0.90, 0.10 + Math.random() * 0.22,',
    '      C_DUST[0], C_DUST[1], C_DUST[2], 0.10 + Math.random() * 0.22,'
  ]
];
for (const [from, to] of repl) {
  if (!s.includes(from)) throw new Error('missing: ' + from.slice(0, 60));
  s = s.replace(from, to);
}
fs.writeFileSync(f, s, 'utf8');
console.log('theme prop patched');

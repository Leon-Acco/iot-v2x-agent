// Fix grabbed comment lines in SplatTruck.vue using line-relative extraction
const fs = require('fs');
const html = fs.readFileSync('E:/dst_agent_car/index (7).html', 'utf8');
const hLines = html.split('\n');
const DASH = String.fromCharCode(0x2500);

function lineAfter(anchor, n) {
  const i = hLines.findIndex(l => l.includes(anchor));
  if (i < 0) throw new Error('anchor missing: ' + anchor);
  return hLines[i + n].trim();
}
function lineBefore(anchor, n) {
  const i = hLines.findIndex(l => l.includes(anchor));
  if (i < 0) throw new Error('anchor missing: ' + anchor);
  return hLines[i - n].trim();
}
// strip comment wrapper and box-drawing dashes, keep core text
function core(line) {
  let t = line.replace(/^\/\*+\s*/, '').replace(/\s*\*+\/$/, '').trim();
  while (t.startsWith(DASH) || t.startsWith('-')) t = t.slice(1).trimStart();
  while (t.endsWith(DASH) || t.endsWith('-')) t = t.slice(0, -1).trimEnd();
  return t.trim();
}

const zhSplat = lineAfter('2.5D Gaussian Splatting holo deck', 1);
const zhFx = lineAfter('2.5D Gaussian Splatting holo deck', 2);
const zhVbo = core(lineBefore('function makeVBO(set)', 2));
const zhImg = core(lineBefore('function buildSplats(img, step)', 2));
const zhFxBuild = core(lineBefore('function buildFx()', 2));
const zhMouse = core(lineBefore('const mouse = {', 2));
const zhFrame = core(lineBefore('let lastT = performance.now()', 2));
console.log('extracted:');
[zhSplat, zhFx, zhVbo, zhImg, zhFxBuild, zhMouse, zhFrame].forEach(z => console.log('  ', z));

const target = 'D:/code/work-space-agent/dst-v2x-agent/frontend/components/login/SplatTruck.vue';
let s = fs.readFileSync(target, 'utf8');

const badLine3 = s.split('\n')[2];
const badLine15 = s.split('\n')[14];
const repl = [
  [badLine3, '  <!-- ' + zhFx + ' -->'],
  [badLine15, '// ' + zhSplat],
  ['// uniform vec2  u_mouse;', '// ' + zhMouse],
  ['// function makeVBO(set) {', '// ' + zhVbo],
  ['// function buildSplats(img, step) { (sorted far-to-near, painter algorithm)', '// ' + zhImg + ' (sorted far-to-near, painter algorithm)'],
  ['// function buildFx() {', '// ' + zhFxBuild],
  ['// @keyframes sweep {', '// ' + zhFrame]
];
for (const [from, to] of repl) {
  if (!s.includes(from)) throw new Error('replace target missing: ' + from.slice(0, 60));
  s = s.replace(from, to);
}
fs.writeFileSync(target, s, 'utf8');
console.log('fixed', target);

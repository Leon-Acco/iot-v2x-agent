const fs = require('fs');
const html = fs.readFileSync('E:/dst_agent_car/index (7).html', 'utf8');
const hLines = html.split('\n');
const DASH = String.fromCharCode(0x2500);
function lineBefore(anchor, n) {
  const i = hLines.findIndex(l => l.includes(anchor));
  if (i < 0) throw new Error('anchor missing: ' + anchor);
  return hLines[i - n].trim();
}
function core(line) {
  let t = line.replace(/^\/\*+\s*/, '').replace(/\s*\*+\/$/, '').trim();
  while (t.startsWith(DASH) || t.startsWith('-')) t = t.slice(1).trimStart();
  while (t.endsWith(DASH) || t.endsWith('-')) t = t.slice(0, -1).trimEnd();
  return t.trim();
}
const zhImg = core(lineBefore('const params = {', 2));
console.log('zhImg =', zhImg);
const target = 'D:/code/work-space-agent/dst-v2x-agent/frontend/components/login/SplatTruck.vue';
let s = fs.readFileSync(target, 'utf8');
const from = '// let truckW = 0, truckH = 0, truckScale = 1; (sorted far-to-near, painter algorithm)';
if (!s.includes(from)) throw new Error('target missing');
s = s.replace(from, '// ' + zhImg + ' (sorted far-to-near, painter algorithm)');
fs.writeFileSync(target, s, 'utf8');
console.log('done');

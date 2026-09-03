// Compile both SFCs with @vue/compiler-sfc (authoritative check)
const path = require('path');
const fs = require('fs');
const feDir = 'D:/code/work-space-agent/dst-v2x-agent/frontend';
const { parse, compileTemplate, compileScript } = require(path.join(feDir, 'node_modules', '@vue', 'compiler-sfc'));

for (const rel of ['pages/login.vue', 'components/login/SplatTruck.vue']) {
  const file = path.join(feDir, rel);
  const src = fs.readFileSync(file, 'utf8');
  const { descriptor, errors } = parse(src, { filename: rel });
  if (errors.length) {
    console.log(rel, 'PARSE ERRORS:', errors.map(e => e.message));
    process.exit(1);
  }
  const id = 'test-' + rel.replace(/\W/g, '');
  try {
    compileScript(descriptor, { id });
  } catch (e) {
    console.log(rel, 'SCRIPT ERROR:', e.message);
    process.exit(1);
  }
  const tpl = compileTemplate({
    source: descriptor.template.content,
    filename: rel,
    id,
    compilerOptions: { whitespace: 'preserve' }
  });
  if (tpl.errors.length) {
    console.log(rel, 'TEMPLATE ERRORS:', tpl.errors.map(e => e.message || e));
    process.exit(1);
  }
  console.log(rel, 'OK');
}

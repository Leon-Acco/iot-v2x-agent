import { createRequire } from "node:module";
const require = createRequire("D:/code/work-space-agent/storytellingnoomo-rebuild/package.json");
const { chromium } = require("playwright");
const browser = await chromium.launch({ channel: "msedge" });
const page = await (await browser.newContext()).newPage();
await page.goto("http://localhost:3000/login", { waitUntil: "domcontentloaded" });
await page.evaluate(async () => {
  await fetch("/api/auth/login", { method: "POST", headers: { "Content-Type": "application/json" }, body: JSON.stringify({ username: "admin", password: "admin123" }) });
});
const out = await page.evaluate(async () => {
  const sum = await (await fetch("/ag-ui/map/fleet-summary")).json();
  const vs = await (await fetch("/ag-ui/map/vehicles")).json();
  const vins = vs.map((v) => v.vin);
  const levels = { 0: 0, 1: 0, 2: 0 };
  vs.forEach((v) => { levels[v.online_level] = (levels[v.online_level] || 0) + 1; });
  return {
    summary: sum,
    rows: vs.length,
    dupVins: vins.length - new Set(vins).size,
    levels,
    hasLevel: vs.length > 0 && vs[0].online_level !== undefined,
  };
});
console.log(JSON.stringify(out, null, 1));
await browser.close();

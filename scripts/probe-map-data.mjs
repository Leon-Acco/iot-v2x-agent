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
  const ps = await (await fetch("/ag-ui/map/province-stats")).json();
  const vs = await (await fetch("/ag-ui/map/vehicles")).json();
  const now = Date.now();
  const ages = vs.map((v) => (now - new Date(v.report_time).getTime()) / 3600000).filter((x) => !isNaN(x));
  ages.sort((a, b) => a - b);
  const brandCount = {};
  for (const v of vs) { const b = v.car_brand_name || "(empty)"; brandCount[b] = (brandCount[b] || 0) + 1; }
  return {
    brandsApi: ps.brands ? ps.brands.slice(0, 5) : null,
    brandFromVehicles: Object.entries(brandCount).sort((a, b) => b[1] - a[1]).slice(0, 5),
    reportAgeHours: { min: ages[0] && ages[0].toFixed(1), p50: ages[Math.floor(ages.length / 2)] && ages[Math.floor(ages.length / 2)].toFixed(1), max: ages[ages.length - 1] && ages[ages.length - 1].toFixed(1) },
    provinces: ps.provinces ? ps.provinces.length : 0,
  };
});
console.log(JSON.stringify(out, null, 1));
await browser.close();

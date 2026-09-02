import { createRequire } from "node:module";
const require = createRequire("D:/code/work-space-agent/storytellingnoomo-rebuild/package.json");
const { chromium } = require("playwright");

const browser = await chromium.launch({ channel: "msedge" });
const ctx = await browser.newContext({ viewport: { width: 1600, height: 900 } });
const page = await ctx.newPage();
const errors = [];
const apiLog = [];
page.on("console", (m) => { if (m.type() === "error") errors.push(m.text().slice(0, 250)); });
page.on("pageerror", (e) => errors.push("PAGEERROR: " + String(e.message).slice(0, 250)));
page.on("response", async (r) => {
  const u = r.url();
  if (u.includes("/ag-ui/map/") || u.includes("/api/auth")) {
    let size = 0;
    try { size = (await r.body()).length; } catch (e) {}
    apiLog.push({ url: u.split("8080").pop() || u.split("3000").pop(), status: r.status(), size });
  }
});
await page.goto("http://localhost:3000/login", { waitUntil: "domcontentloaded", timeout: 60000 });
// login via the API so the cookie session is set
const loginRes = await page.evaluate(async () => {
  const r = await fetch("/api/auth/login", {
    method: "POST",
    headers: { "Content-Type": "application/json" },
    body: JSON.stringify({ username: "admin", password: "admin123" }),
  });
  return r.status;
});
await page.goto("http://localhost:3000/map", { waitUntil: "networkidle", timeout: 60000 });
await page.waitForTimeout(6000);
const kpi = await page.evaluate(() => {
  const txt = (el) => (el ? el.textContent.trim() : null);
  const cards = [...document.querySelectorAll(".card h3")].map((h) => h.textContent.trim());
  const kpis = [...document.querySelectorAll(".kpi b")].map((b) => b.textContent.trim());
  return { cards, kpis };
});
const dupCheck = await page.evaluate(async () => {
  const r = await fetch("/ag-ui/map/vehicles");
  const list = await r.json();
  const vins = list.map((v) => v.vin);
  const dup = vins.length - new Set(vins).size;
  const online = list.filter((v) => Number(v.online) === 1).length;
  const noLng = list.filter((v) => typeof v.lng !== "number" || typeof v.lat !== "number").length;
  return { rows: list.length, dupVins: dup, online, missingLngLat: noLng };
});
await page.screenshot({ path: "D:/code/work-space-agent/dst-v2x-agent/.playwright-mcp/map-page.png" });
console.log(JSON.stringify({ loginRes, kpi, dupCheck, apiLog, errorCount: errors.length, errors: errors.slice(0, 10) }, null, 1));
await browser.close();

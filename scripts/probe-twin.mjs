import { createRequire } from "node:module";
const require = createRequire("D:/code/work-space-agent/storytellingnoomo-rebuild/package.json");
const { chromium } = require("playwright");

const browser = await chromium.launch({ channel: "msedge" });
const ctx = await browser.newContext({ viewport: { width: 1680, height: 950 } });
const page = await ctx.newPage();
const errors = [];
page.on("console", (m) => { if (m.type() === "error") errors.push(m.text().slice(0, 200)); });
page.on("pageerror", (e) => errors.push("PAGEERROR: " + String(e.message).slice(0, 250)));

await page.goto("http://localhost:3000/login", { waitUntil: "domcontentloaded", timeout: 60000 });
const loginRes = await page.evaluate(async () => {
  const r = await fetch("/api/auth/login", {
    method: "POST",
    headers: { "Content-Type": "application/json" },
    body: JSON.stringify({ username: "admin", password: "admin123" }),
  });
  return r.status;
});
await page.goto("http://localhost:3000/map", { waitUntil: "networkidle", timeout: 60000 });
await page.waitForTimeout(7000);

const probe = await page.evaluate(() => {
  const q = (s) => [...document.querySelectorAll(s)];
  return {
    hasTwin: !!document.querySelector(".twin"),
    kpiTitles: q(".kpi-title").map((e) => e.textContent.trim()),
    kpiNums: q(".kpi-num b").map((e) => e.textContent.trim()),
    chartCards: q(".charts .card h3").map((e) => e.textContent.trim()),
    alertRows: q(".at-body .at-row").length,
    qaBtns: q(".qa-btn").map((e) => e.textContent.trim()),
    navGroups: q(".nav-group .group-title").map((e) => e.textContent.trim()),
    modeBtns: q(".mode-switch button").map((e) => e.textContent.trim()),
    canvasCount: q(".stage canvas").length,
  };
});
await page.screenshot({ path: "D:/code/work-space-agent/dst-v2x-agent/.tmp-verify/twin-page.png", fullPage: true });

// interact: heat mode + region pick + search
await page.click(".mode-switch button:nth-child(2)");
await page.waitForTimeout(1200);
await page.screenshot({ path: "D:/code/work-space-agent/dst-v2x-agent/.tmp-verify/twin-heat.png" });
await page.click(".mode-switch button:nth-child(1)");

// click first region TOP5 row
const regionPick = await page.evaluate(() => {
  const el = document.querySelector(".charts .rank-item.clickable");
  if (el) { el.click(); return el.textContent.trim(); }
  return null;
});
await page.waitForTimeout(2500);
const drilled = await page.evaluate(() => ({
  backChip: !!document.querySelector(".back-chip"),
  filterVal: document.querySelector(".fb-select") ? document.querySelector(".fb-select").value : null,
}));
await page.screenshot({ path: "D:/code/work-space-agent/dst-v2x-agent/.tmp-verify/twin-drill.png" });

console.log(JSON.stringify({ loginRes, probe, regionPick, drilled, errorCount: errors.length, errors: errors.slice(0, 8) }, null, 1));
await browser.close();

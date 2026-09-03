import { createRequire } from 'module'
const require = createRequire('D:/code/work-space-agent/storytellingnoomo-rebuild/package.json')
const { chromium } = require('playwright')

const out = process.argv[2] || 'D:/code/work-space-agent/dst-v2x-agent/logs/shot-chat2.png'
const question = process.argv[3] || '近7天各类告警占比，画成饼图'

const browser = await chromium.launch({ channel: 'msedge' })
const page = await browser.newPage({ viewport: { width: 1600, height: 900 } })
const errors = []
page.on('pageerror', e => errors.push('PAGEERROR ' + String(e).slice(0, 200)))

await page.goto('http://localhost:3000/login', { waitUntil: 'networkidle', timeout: 45000 })
await page.waitForTimeout(1200)
await page.click('button.submit')
await page.waitForTimeout(5200)
await page.goto('http://localhost:3000/chat', { waitUntil: 'networkidle', timeout: 45000 })
await page.waitForTimeout(1500)

// type question and send
await page.fill('input.pill-input', question)
await page.keyboard.press('Enter')
console.log('question sent')
// wait for run to finish (viz card or timeout)
try {
  await page.waitForSelector('.viz-card', { timeout: 120000 })
  console.log('viz card appeared')
} catch (e) { console.log('no viz card within 120s') }
await page.waitForTimeout(3000)
await page.screenshot({ path: out })
console.log('shot saved:', out)
console.log('errors:', errors.length ? errors.slice(0, 5) : 'none')
await browser.close()

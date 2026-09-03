import { createRequire } from 'module'
const require = createRequire('D:/code/work-space-agent/storytellingnoomo-rebuild/package.json')
const { chromium } = require('playwright')

const url = process.argv[2] || 'http://localhost:3000/chat'
const out = process.argv[3] || 'D:/code/work-space-agent/dst-v2x-agent/logs/shot-chat.png'

const browser = await chromium.launch({ channel: 'msedge' })
const page = await browser.newPage({ viewport: { width: 1600, height: 900 } })
const errors = []
page.on('console', m => { if (m.type() === 'error') errors.push(m.text().slice(0, 200)) })
page.on('pageerror', e => errors.push('PAGEERROR ' + String(e).slice(0, 200)))

// login first
await page.goto('http://localhost:3000/login', { waitUntil: 'networkidle', timeout: 45000 })
await page.waitForTimeout(1200)
await page.click('button.submit')
await page.waitForTimeout(2600)
// transition page auto-forwards to /map
await page.waitForTimeout(2600)
console.log('after login url:', page.url())

await page.goto(url, { waitUntil: 'networkidle', timeout: 45000 }).catch(e => errors.push('NAV ' + e.message))
await page.waitForTimeout(2500)
await page.screenshot({ path: out })
console.log('shot saved:', out)
console.log('console errors:', errors.length ? errors.slice(0, 6) : 'none')
await browser.close()

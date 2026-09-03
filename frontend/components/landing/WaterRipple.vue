<template>
  <!-- full-page water ripple simulation (ported from Office_Agent lp-ripple) -->
  <canvas ref="cv" class="water-ripple" @pointermove="onMove" @pointerdown="onDown"></canvas>
</template>

<script setup>
const cv = ref(null)
let sim = null
let rafId = null
let rainTimer = null
let disposed = false

const CELL = 3.5
const DAMPING = 0.985
const LIGHT_K = 1.3

function syncSize() {
  const el = cv.value
  if (!el) return false
  const w = el.clientWidth
  const h = el.clientHeight
  if (!w || !h) return false
  const dpr = Math.min(window.devicePixelRatio || 1, 1.25)
  const W = Math.round(w * dpr)
  const H = Math.round(h * dpr)
  if (el.width !== W || el.height !== H) {
    el.width = W
    el.height = H
  }
  const sw = Math.max(48, Math.round(w / CELL))
  const sh = Math.max(48, Math.round(h / CELL))
  if (!sim || sim.w !== sw || sim.h !== sh) {
    const off = document.createElement('canvas')
    off.width = sw
    off.height = sh
    sim = {
      w: sw, h: sh,
      cur: new Float32Array(sw * sh),
      prev: new Float32Array(sw * sh),
      off,
      offCtx: off.getContext('2d')
    }
    sim.img = sim.offCtx.createImageData(sw, sh)
  }
  return true
}

function drop(x, y, radius, strength) {
  if (!sim) return
  const cx = Math.round(x / CELL)
  const cy = Math.round(y / CELL)
  for (let dy = -radius; dy <= radius; dy++) {
    for (let dx = -radius; dx <= radius; dx++) {
      const d = Math.sqrt(dx * dx + dy * dy)
      if (d > radius) continue
      const px = cx + dx
      const py = cy + dy
      if (px < 1 || py < 1 || px >= sim.w - 1 || py >= sim.h - 1) continue
      sim.cur[py * sim.w + px] += strength * (1 - d / (radius + 1))
    }
  }
}

function step() {
  const w = sim.w
  const h = sim.h
  let cur = sim.cur
  let prev = sim.prev
  for (let y = 1; y < h - 1; y++) {
    const row = y * w
    for (let x = 1; x < w - 1; x++) {
      const i = row + x
      prev[i] = ((cur[i - 1] + cur[i + 1] + cur[i - w] + cur[i + w]) * 0.5 - prev[i]) * DAMPING
    }
  }
  sim.prev = cur
  sim.cur = prev
}

function render() {
  const el = cv.value
  const ctx = el.getContext('2d')
  const w = sim.w
  const h = sim.h
  const cur = sim.cur
  const data = sim.img.data
  for (let y = 1; y < h - 1; y++) {
    const row = y * w
    for (let x = 1; x < w - 1; x++) {
      const i = row + x
      const g = ((cur[i + 1] - cur[i - 1]) + (cur[i + w] - cur[i - w]) * 0.6) * LIGHT_K
      const j = i * 4
      if (g < 0) {
        let a = -g
        if (a > 150) a = 150
        data[j] = 11
        data[j + 1] = 74
        data[j + 2] = 66
        data[j + 3] = a
      } else {
        let a = g
        if (a > 170) a = 170
        data[j] = 255
        data[j + 1] = 255
        data[j + 2] = 255
        data[j + 3] = a
      }
    }
  }
  sim.offCtx.putImageData(sim.img, 0, 0)
  ctx.clearRect(0, 0, el.width, el.height)
  ctx.imageSmoothingQuality = 'high'
  ctx.drawImage(sim.off, 0, 0, el.width, el.height)
}

function frame() {
  if (disposed) return
  if (!syncSize()) return
  step()
  render()
  rafId = requestAnimationFrame(frame)
}

function onMove(e) {
  const rect = cv.value.getBoundingClientRect()
  drop(e.clientX - rect.left, e.clientY - rect.top, 3, 26)
}

function onDown(e) {
  const rect = cv.value.getBoundingClientRect()
  drop(e.clientX - rect.left, e.clientY - rect.top, 5, 60)
}

onMounted(() => {
  if (!syncSize()) return
  // seed a few drops so the water is alive on load
  const el = cv.value
  for (let i = 0; i < 8; i++) {
    drop(Math.random() * el.clientWidth, Math.random() * el.clientHeight, 4, 50)
  }
  rainTimer = setInterval(() => {
    const el2 = cv.value
    if (!el2) return
    drop(Math.random() * el2.clientWidth, Math.random() * el2.clientHeight, 2 + Math.random() * 3, 18 + Math.random() * 30)
  }, 900)
  rafId = requestAnimationFrame(frame)
})

onBeforeUnmount(() => {
  disposed = true
  if (rafId) cancelAnimationFrame(rafId)
  if (rainTimer) clearInterval(rainTimer)
})
</script>

<style scoped>
.water-ripple {
  position: absolute; inset: 0; width: 100%; height: 100%; z-index: 1;
}
</style>

<template>
  <!-- 真 3D 粒子车体：车身/车顶/驾驶舱/玻璃分层 + 4 轮带毯 + 前后灯 + 车底悬浮 + 背景数字地球 + 地面能量环，拖动旋转 -->
  <div ref="wrap" class="truck-scene" @pointerdown="onDown" @pointermove="onMove" @pointerup="onUp" @pointerleave="onUp">
    <canvas ref="cv"></canvas>
  </div>
</template>

<script setup>
const props = defineProps({
  vehicles: { type: Array, default: () => [] }
})

const wrap = ref(null)
const cv = ref(null)
let renderer = null
let scene = null
let camera = null
let carGroup = null
let globeGroup = null
let rings = []
let rafId = null
let disposed = false

let dragging = false
let lastX = 0
let lastY = 0
let rotY = -0.5
let rotX = 0.12
let velY = 0.002
let tiltX = 0
let tiltZ = 0

function loadThree() {
  if (window.THREE) return Promise.resolve()
  return new Promise((resolve, reject) => {
    const s = document.createElement('script')
    s.src = '/vendor/three.min.js'
    s.onload = resolve
    s.onerror = reject
    document.head.appendChild(s)
  })
}

function makeSprite() {
  const c = document.createElement('canvas')
  c.width = 64; c.height = 64
  const ctx = c.getContext('2d')
  const g = ctx.createRadialGradient(32, 32, 0, 32, 32, 32)
  g.addColorStop(0, 'rgba(255,255,255,1)')
  g.addColorStop(0.45, 'rgba(255,255,255,.8)')
  g.addColorStop(1, 'rgba(255,255,255,0)')
  ctx.fillStyle = g
  ctx.fillRect(0, 0, 64, 64)
  return new window.THREE.CanvasTexture(c)
}

let SPRITE = null

function pushColor(arr, hex, brightness) {
  const c = new window.THREE.Color(hex).multiplyScalar(brightness)
  arr.push(c.r, c.g, c.b)
}

// 盒体表面粒子采样：按面积加权，法线假漫反射明暗去塑料感
function sampleBox(positions, colors, box, hex, density) {
  const [x0, x1, y0, y1, z0, z1] = box
  const dx = x1 - x0, dy = y1 - y0, dz = z1 - z0
  const faces = [
    { area: dx * dz, n: 'top', b: 1.16 },
    { area: dx * dz, n: 'bottom', b: 0.72 },
    { area: dy * dz, n: 'front', b: 1.08 },
    { area: dy * dz, n: 'back', b: 0.86 },
    { area: dx * dy, n: 'left', b: 0.97 },
    { area: dx * dy, n: 'right', b: 0.97 }
  ]
  for (const f of faces) {
    const count = Math.max(4, Math.round(f.area * density))
    for (let i = 0; i < count; i++) {
      let x = x0 + Math.random() * dx
      let y = y0 + Math.random() * dy
      let z = z0 + Math.random() * dz
      if (f.n === 'top') y = y1
      else if (f.n === 'bottom') y = y0
      else if (f.n === 'front') x = x1
      else if (f.n === 'back') x = x0
      else if (f.n === 'left') z = z0
      else z = z1
      const j = 0.012
      positions.push(
        x + (Math.random() - 0.5) * j,
        y + (Math.random() - 0.5) * j,
        z + (Math.random() - 0.5) * j
      )
      pushColor(colors, hex, f.b * (0.92 + Math.random() * 0.16))
    }
  }
}

function sampleTorus(positions, colors, cx, cy, cz, ringR, tubeR, hex, count) {
  for (let i = 0; i < count; i++) {
    const a = Math.random() * Math.PI * 2
    const r = ringR + (Math.random() - 0.5) * 2 * tubeR
    positions.push(cx + Math.cos(a) * r, cy + Math.sin(a) * r, cz + (Math.random() - 0.5) * tubeR)
    pushColor(colors, hex, 0.85 + Math.random() * 0.3)
  }
}

function sampleDisc(positions, colors, cx, cy, cz, r, hex, count) {
  for (let i = 0; i < count; i++) {
    const a = Math.random() * Math.PI * 2
    const rr = Math.sqrt(Math.random()) * r
    positions.push(cx + Math.cos(a) * rr, cy + Math.sin(a) * rr, cz + (Math.random() - 0.5) * 0.02)
    pushColor(colors, hex, 0.95 + Math.random() * 0.2)
  }
}

function sampleCluster(positions, colors, cx, cy, cz, spread, hex, count, boost) {
  for (let i = 0; i < count; i++) {
    positions.push(
      cx + (Math.random() - 0.5) * spread[0],
      cy + (Math.random() - 0.5) * spread[1],
      cz + (Math.random() - 0.5) * spread[2]
    )
    pushColor(colors, hex, boost * (0.8 + Math.random() * 0.4))
  }
}

function makePoints(positions, colors, size, opacity) {
  const THREE = window.THREE
  const geo = new THREE.BufferGeometry()
  geo.setAttribute('position', new THREE.BufferAttribute(new Float32Array(positions), 3))
  geo.setAttribute('color', new THREE.BufferAttribute(new Float32Array(colors), 3))
  const mat = new THREE.PointsMaterial({
    size, vertexColors: true, map: SPRITE,
    transparent: true, opacity, depthWrite: false, sizeAttenuation: true
  })
  return new THREE.Points(geo, mat)
}

// 车体分层构建：车身 / 车顶 / 驾驶舱 / 玻璃 / 底盘 / 4 轮 / 前灯 / 尾灯带 / 车底悬浮粒子
function buildCar() {
  const THREE = window.THREE
  carGroup = new THREE.Group()

  // 车身主体（货厢）浅蓝白
  const body = []
  const bodyCol = []
  sampleBox(body, bodyCol, [-2.55, -0.35, 0.42, 1.52, -0.56, 0.56], 0x9DBDF5, 620)
  // 车顶加亮层
  sampleBox(body, bodyCol, [-2.55, -0.35, 1.50, 1.56, -0.56, 0.56], 0xD6E4FF, 300)
  carGroup.add(makePoints(body, bodyCol, 0.028, 0.95))

  // 驾驶舱下部（中蓝）
  const cab = []
  const cabCol = []
  sampleBox(cab, cabCol, [-0.35, 0.72, 0.18, 0.82, -0.56, 0.56], 0x7FA8F0, 420)
  // 驾驶舱上部（略窄）
  sampleBox(cab, cabCol, [-0.30, 0.55, 0.82, 1.18, -0.48, 0.48], 0x8FB6F4, 300)
  // 底盘深色条
  sampleBox(cab, cabCol, [-2.6, 0.85, 0.14, 0.30, -0.50, 0.50], 0x3A4A63, 160)
  carGroup.add(makePoints(cab, cabCol, 0.028, 0.95))

  // 玻璃层：前模款降舱前面上半 + 侧窗，亮青
  const glass = []
  const glassCol = []
  sampleBox(glass, glassCol, [0.55, 0.72, 0.82, 1.14, -0.46, 0.46], 0x6FE3FF, 260)
  sampleBox(glass, glassCol, [-0.28, 0.50, 0.86, 1.12, -0.49, -0.46], 0x6FE3FF, 120)
  sampleBox(glass, glassCol, [-0.28, 0.50, 0.86, 1.12, 0.46, 0.49], 0x6FE3FF, 120)
  carGroup.add(makePoints(glass, glassCol, 0.034, 0.9))

  // 4 轮胎 + 轮毯（前 1 后 1 货厢 2）
  const wheelXs = [0.55, -0.55, -1.55, -2.2]
  const wheels = []
  const wheelsCol = []
  const hubs = []
  const hubsCol = []
  for (const wx of wheelXs) {
    for (const wz of [-0.58, 0.58]) {
      sampleTorus(wheels, wheelsCol, wx, 0.30, wz, 0.24, 0.07, 0x2E3B52, 420)
      sampleDisc(hubs, hubsCol, wx, 0.30, wz, 0.11, 0xC9D9F2, 110)
    }
  }
  carGroup.add(makePoints(wheels, wheelsCol, 0.032, 0.95))
  carGroup.add(makePoints(hubs, hubsCol, 0.028, 0.95))

  // 前灯（暖白）+ 尾部红色灯带
  const lights = []
  const lightsCol = []
  sampleCluster(lights, lightsCol, 0.76, 0.40, -0.38, [0.06, 0.12, 0.18], 0xFFF3C4, 40, 1.7)
  sampleCluster(lights, lightsCol, 0.76, 0.40, 0.38, [0.06, 0.12, 0.18], 0xFFF3C4, 40, 1.7)
  sampleCluster(lights, lightsCol, -2.62, 0.52, 0, [0.04, 0.08, 1.02], 0xFF5A5A, 70, 1.6)
  carGroup.add(makePoints(lights, lightsCol, 0.05, 0.95))

  // 车底悬浮粒子尘
  const dust = []
  const dustCol = []
  for (let i = 0; i < 380; i++) {
    dust.push(
      -2.8 + Math.random() * 3.9,
      0.02 + Math.random() * 0.16,
      -0.75 + Math.random() * 1.5
    )
    pushColor(dustCol, 0x93C5FD, 0.7 + Math.random() * 0.6)
  }
  carGroup.add(makePoints(dust, dustCol, 0.022, 0.7))

  carGroup.position.y = 0.35
  scene.add(carGroup)
}

// 背景粒子数字地球（淡蓝、大半径、慢转）
function buildGlobe() {
  const THREE = window.THREE
  globeGroup = new THREE.Group()
  const pos = []
  const col = []
  const COUNT = 7000
  const R = 3.4
  for (let i = 0; i < COUNT; i++) {
    const y = 1 - (i / (COUNT - 1)) * 2
    const radius = Math.sqrt(1 - y * y)
    const theta = i * 2.399963229728653
    pos.push(Math.cos(theta) * radius * R, y * R, Math.sin(theta) * radius * R)
    pushColor(col, Math.random() < 0.04 ? 0x60A5FA : 0xC7D8F0, 0.8 + Math.random() * 0.3)
  }
  globeGroup.add(makePoints(pos, col, 0.026, 0.55))
  globeGroup.position.set(0.4, 0.9, -2.4)
  scene.add(globeGroup)
}

// 地面能量环：扩散脉冲圆环 + 稀疏地面粒子盘
function buildGround() {
  const THREE = window.THREE
  for (let i = 0; i < 3; i++) {
    const curve = new THREE.EllipseCurve(0, 0, 1, 1, 0, Math.PI * 2, false, 0)
    const geo = new THREE.BufferGeometry().setFromPoints(curve.getPoints(120))
    const mat = new THREE.LineBasicMaterial({ color: 0x60A5FA, transparent: true, opacity: 0.0 })
    const ring = new THREE.LineLoop(geo, mat)
    ring.rotation.x = Math.PI / 2
    ring.position.y = 0.02
    ring.userData.phase = i / 3
    rings.push(ring)
    scene.add(ring)
  }
  // 地面粒子盘
  const pos = []
  const col = []
  for (let i = 0; i < 1400; i++) {
    const a = Math.random() * Math.PI * 2
    const r = 0.6 + Math.sqrt(Math.random()) * 3.6
    pos.push(Math.cos(a) * r - 0.4, 0.02 + Math.random() * 0.015, Math.sin(a) * r)
    pushColor(col, 0x93C5FD, 0.5 + Math.random() * 0.5)
  }
  scene.add(makePoints(pos, col, 0.02, 0.5))
}

function onDown(e) {
  dragging = true
  lastX = e.clientX
  lastY = e.clientY
  if (wrap.value && wrap.value.setPointerCapture) {
    try { wrap.value.setPointerCapture(e.pointerId) } catch (err) { /* ignore */ }
  }
}

function onMove(e) {
  if (!wrap.value) return
  const rect = wrap.value.getBoundingClientRect()
  const nx = (e.clientX - rect.left) / rect.width - 0.5
  const ny = (e.clientY - rect.top) / rect.height - 0.5
  if (dragging) {
    rotY += (e.clientX - lastX) * 0.006
    rotX = Math.max(-0.35, Math.min(0.6, rotX + (e.clientY - lastY) * 0.004))
    lastX = e.clientX
    lastY = e.clientY
    velY = 0
  } else {
    tiltX = ny * 0.14
    tiltZ = nx * 0.2
  }
}

function onUp() {
  dragging = false
}

function animate(now) {
  if (disposed) return
  rafId = requestAnimationFrame(animate)
  const t = (now || 0) / 1000
  if (!dragging) {
    rotY += velY
    if (velY < 0.0016) velY += 0.00002
  }
  if (carGroup) {
    carGroup.rotation.y += (rotY - carGroup.rotation.y) * 0.08
    carGroup.rotation.x += ((rotX + tiltX) - carGroup.rotation.x) * 0.06
    carGroup.rotation.z += (tiltZ * 0.3 - carGroup.rotation.z) * 0.06
    carGroup.position.y = 0.35 + Math.sin(t * 1.1) * 0.045
  }
  if (globeGroup) globeGroup.rotation.y += 0.0008
  // 能量环脉冲
  for (const ring of rings) {
    const k = ((t * 0.28 + ring.userData.phase) % 1)
    const s = 0.8 + k * 3.4
    ring.scale.set(s, s, s)
    ring.material.opacity = (1 - k) * 0.4
  }
  if (renderer && scene && camera) renderer.render(scene, camera)
  if (carGroup) window.__truckRotY = carGroup.rotation.y
}

function onResize() {
  if (!renderer || !wrap.value || !camera) return
  const w = wrap.value.clientWidth
  const h = wrap.value.clientHeight
  if (!w || !h) return
  renderer.setSize(w, h)
  camera.aspect = w / h
  camera.updateProjectionMatrix()
}

onMounted(async () => {
  try {
    await loadThree()
    const THREE = window.THREE
    SPRITE = makeSprite()
    scene = new THREE.Scene()
    camera = new THREE.PerspectiveCamera(40, 1, 0.1, 100)
    camera.position.set(3.6, 2.0, 6.2)
    camera.lookAt(-0.5, 0.9, 0)
    renderer = new THREE.WebGLRenderer({ canvas: cv.value, antialias: true, alpha: true })
    renderer.setClearColor(0x000000, 0)
    renderer.setPixelRatio(Math.min(window.devicePixelRatio || 1, 2))
    buildGlobe()
    buildGround()
    buildCar()
    onResize()
    window.addEventListener('resize', onResize)
    animate()
  } catch (e) { /* WebGL 不可用 */ }
})

onBeforeUnmount(() => {
  disposed = true
  if (rafId) cancelAnimationFrame(rafId)
  window.removeEventListener('resize', onResize)
  if (renderer) {
    renderer.dispose()
    renderer = null
  }
})
</script>

<style scoped>
.truck-scene {
  position: fixed; inset: 0; z-index: 0;
  cursor: grab; touch-action: none;
}
.truck-scene:active { cursor: grabbing; }
.truck-scene canvas { width: 100%; height: 100%; display: block; }
</style>

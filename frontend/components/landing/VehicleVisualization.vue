<template>
  <!-- 车辆数字孪生主视觉：点云 + 线框 + 半透实体，扫描光带/数据流/雷达环，鼠标视差免拖拽 -->
  <div ref="wrap" class="viz-scene" @pointerdown="onDown" @pointermove="onMove" @pointerup="onUp" @pointerleave="onUp">
    <canvas ref="cv" aria-hidden="true"></canvas>
  </div>
</template>

<script setup>
const wrap = ref(null)
const cv = ref(null)
let renderer = null
let scene = null
let camera = null
let carGroup = null
let globeGroup = null
let scanBand = null
let streamDots = null
let streamCurve = null
let rings = []
let radar = null
let rafId = null
let disposed = false

let dragging = false
let lastX = 0
let lastY = 0
let rotY = -0.55
let rotX = 0.12
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
      const j = 0.01
      positions.push(
        x + (Math.random() - 0.5) * j,
        y + (Math.random() - 0.5) * j,
        z + (Math.random() - 0.5) * j
      )
      pushColor(colors, hex, f.b * (0.92 + Math.random() * 0.16))
    }
  }
}

// 盒体 12 条棱边加亮粒子：轮廓一眼可读
function sampleEdges(positions, colors, box, hex, perEdge) {
  const [x0, x1, y0, y1, z0, z1] = box
  const corners = [
    [x0, y0, z0], [x1, y0, z0], [x0, y1, z0], [x1, y1, z0],
    [x0, y0, z1], [x1, y0, z1], [x0, y1, z1], [x1, y1, z1]
  ]
  const edges = [
    [0, 1], [2, 3], [4, 5], [6, 7],
    [0, 2], [1, 3], [4, 6], [5, 7],
    [0, 4], [1, 5], [2, 6], [3, 7]
  ]
  for (const [a, b] of edges) {
    for (let i = 0; i < perEdge; i++) {
      const t = i / (perEdge - 1)
      positions.push(
        corners[a][0] + (corners[b][0] - corners[a][0]) * t,
        corners[a][1] + (corners[b][1] - corners[a][1]) * t,
        corners[a][2] + (corners[b][2] - corners[a][2]) * t
      )
      pushColor(colors, hex, 0.9 + Math.random() * 0.3)
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

// 半透明实体 + 线框：托住点云轮廓
function addSolidAndWire(group, box, hex) {
  const THREE = window.THREE
  const [x0, x1, y0, y1, z0, z1] = box
  const geo = new THREE.BoxGeometry(x1 - x0, y1 - y0, z1 - z0)
  const solid = new THREE.Mesh(geo, new THREE.MeshBasicMaterial({
    color: hex, transparent: true, opacity: 0.06, depthWrite: false
  }))
  solid.position.set((x0 + x1) / 2, (y0 + y1) / 2, (z0 + z1) / 2)
  group.add(solid)
  const edges = new THREE.LineSegments(
    new THREE.EdgesGeometry(geo),
    new THREE.LineBasicMaterial({ color: 0x2563EB, transparent: true, opacity: 0.32 })
  )
  edges.position.copy(solid.position)
  group.add(edges)
}

const TRAILER = [-2.55, -0.35, 0.42, 1.52, -0.56, 0.56]
const CAB_LO = [-0.35, 0.72, 0.18, 0.82, -0.56, 0.56]
const CAB_HI = [-0.30, 0.55, 0.82, 1.18, -0.48, 0.48]

function buildCar() {
  const THREE = window.THREE
  carGroup = new THREE.Group()

  // 半透实体 + 线框
  addSolidAndWire(carGroup, TRAILER, 0x2563EB)
  addSolidAndWire(carGroup, CAB_LO, 0x2563EB)
  addSolidAndWire(carGroup, CAB_HI, 0x38BDF8)

  // 点云分层
  const body = []
  const bodyCol = []
  sampleBox(body, bodyCol, TRAILER, 0x9DBDF5, 560)
  sampleBox(body, bodyCol, [-2.55, -0.35, 1.50, 1.56, -0.56, 0.56], 0xD6E4FF, 280)
  carGroup.add(makePoints(body, bodyCol, 0.026, 0.95))

  const cab = []
  const cabCol = []
  sampleBox(cab, cabCol, CAB_LO, 0x7FA8F0, 400)
  sampleBox(cab, cabCol, CAB_HI, 0x8FB6F4, 280)
  sampleBox(cab, cabCol, [-2.6, 0.85, 0.14, 0.30, -0.50, 0.50], 0x3A4A63, 150)
  carGroup.add(makePoints(cab, cabCol, 0.026, 0.95))

  const glass = []
  const glassCol = []
  sampleBox(glass, glassCol, [0.55, 0.72, 0.82, 1.14, -0.46, 0.46], 0x38BDF8, 240)
  sampleBox(glass, glassCol, [-0.28, 0.50, 0.86, 1.12, -0.49, -0.46], 0x38BDF8, 110)
  sampleBox(glass, glassCol, [-0.28, 0.50, 0.86, 1.12, 0.46, 0.49], 0x38BDF8, 110)
  carGroup.add(makePoints(glass, glassCol, 0.032, 0.9))

  // 棱边加亮粒子：轮廓清晰化
  const edge = []
  const edgeCol = []
  sampleEdges(edge, edgeCol, TRAILER, 0x2563EB, 26)
  sampleEdges(edge, edgeCol, CAB_LO, 0x2563EB, 20)
  sampleEdges(edge, edgeCol, CAB_HI, 0x38BDF8, 18)
  carGroup.add(makePoints(edge, edgeCol, 0.04, 0.95))

  // 4 轮 + 毯
  const wheelXs = [0.55, -0.55, -1.55, -2.2]
  const wheels = []
  const wheelsCol = []
  const hubs = []
  const hubsCol = []
  for (const wx of wheelXs) {
    for (const wz of [-0.58, 0.58]) {
      sampleTorus(wheels, wheelsCol, wx, 0.30, wz, 0.24, 0.07, 0x1E293B, 400)
      sampleDisc(hubs, hubsCol, wx, 0.30, wz, 0.11, 0xC9D9F2, 100)
    }
  }
  carGroup.add(makePoints(wheels, wheelsCol, 0.03, 0.95))
  carGroup.add(makePoints(hubs, hubsCol, 0.026, 0.95))

  // 前灯 + 尾部红色风险灯带
  const lights = []
  const lightsCol = []
  sampleCluster(lights, lightsCol, 0.76, 0.40, -0.38, [0.06, 0.12, 0.18], 0xFFF3C4, 40, 1.7)
  sampleCluster(lights, lightsCol, 0.76, 0.40, 0.38, [0.06, 0.12, 0.18], 0xFFF3C4, 40, 1.7)
  sampleCluster(lights, lightsCol, -2.62, 0.52, 0, [0.04, 0.08, 1.02], 0xEF4444, 70, 1.6)
  carGroup.add(makePoints(lights, lightsCol, 0.048, 0.95))

  // 车底悬浮尘
  const dust = []
  const dustCol = []
  for (let i = 0; i < 320; i++) {
    dust.push(-2.8 + Math.random() * 3.9, 0.02 + Math.random() * 0.16, -0.75 + Math.random() * 1.5)
    pushColor(dustCol, 0x93C5FD, 0.7 + Math.random() * 0.6)
  }
  carGroup.add(makePoints(dust, dustCol, 0.02, 0.7))

  carGroup.position.y = 0.35
  scene.add(carGroup)
}

// 扫描光带：周期性扫过车体
function buildScanBand() {
  const THREE = window.THREE
  const geo = new THREE.PlaneGeometry(1.7, 0.5)
  const mat = new THREE.MeshBasicMaterial({
    color: 0x38BDF8, transparent: true, opacity: 0.0,
    side: THREE.DoubleSide, depthWrite: false
  })
  scanBand = new THREE.Mesh(geo, mat)
  scanBand.rotation.y = Math.PI / 2
  scanBand.position.y = 0.9
  scene.add(scanBand)
}

// 数据粒子流：沿曲线环绕车体流动
function buildStream() {
  const THREE = window.THREE
  streamCurve = new THREE.CatmullRomCurve3([
    new THREE.Vector3(-3.4, 0.05, 1.3),
    new THREE.Vector3(-1.2, 0.7, 1.6),
    new THREE.Vector3(0.9, 1.5, 1.0),
    new THREE.Vector3(1.4, 0.9, -0.9),
    new THREE.Vector3(-0.6, 0.15, -1.5),
    new THREE.Vector3(-3.4, 0.05, -0.6)
  ], true)
  const COUNT = 90
  const pos = new Float32Array(COUNT * 3)
  const col = []
  for (let i = 0; i < COUNT; i++) pushColor(col, 0x38BDF8, 0.8 + Math.random() * 0.5)
  const geo = new THREE.BufferGeometry()
  geo.setAttribute('position', new THREE.BufferAttribute(pos, 3))
  geo.setAttribute('color', new THREE.BufferAttribute(new Float32Array(col), 3))
  const mat = new THREE.PointsMaterial({
    size: 0.045, vertexColors: true, map: SPRITE,
    transparent: true, opacity: 0.85, depthWrite: false
  })
  streamDots = new THREE.Points(geo, mat)
  scene.add(streamDots)
}

// 背景数字地球 + 地面雷达环 + 扫描扇区
function buildEnv() {
  const THREE = window.THREE
  globeGroup = new THREE.Group()
  const pos = []
  const col = []
  const COUNT = 6000
  const R = 3.6
  for (let i = 0; i < COUNT; i++) {
    const y = 1 - (i / (COUNT - 1)) * 2
    const radius = Math.sqrt(1 - y * y)
    const theta = i * 2.399963229728653
    pos.push(Math.cos(theta) * radius * R, y * R, Math.sin(theta) * radius * R)
    pushColor(col, Math.random() < 0.04 ? 0x60A5FA : 0xC7D8F0, 0.75 + Math.random() * 0.3)
  }
  globeGroup.add(makePoints(pos, col, 0.024, 0.45))
  globeGroup.position.set(0.4, 0.9, -2.8)
  scene.add(globeGroup)

  for (let i = 0; i < 3; i++) {
    const curve = new THREE.EllipseCurve(0, 0, 1, 1, 0, Math.PI * 2, false, 0)
    const geo = new THREE.BufferGeometry().setFromPoints(curve.getPoints(120))
    const mat = new THREE.LineBasicMaterial({ color: 0x60A5FA, transparent: true, opacity: 0 })
    const ring = new THREE.LineLoop(geo, mat)
    ring.rotation.x = Math.PI / 2
    ring.position.y = 0.02
    ring.userData.phase = i / 3
    rings.push(ring)
    scene.add(ring)
  }

  // 雷达扇区
  const shape = new THREE.Shape()
  shape.moveTo(0, 0)
  shape.arc(0, 0, 2.8, -0.35, 0.35)
  shape.lineTo(0, 0)
  const geo = new THREE.ShapeGeometry(shape)
  radar = new THREE.Mesh(geo, new THREE.MeshBasicMaterial({
    color: 0x60A5FA, transparent: true, opacity: 0.12, side: THREE.DoubleSide, depthWrite: false
  }))
  radar.rotation.x = -Math.PI / 2
  radar.position.set(-0.5, 0.03, 0)
  scene.add(radar)
}

function onDown(e) {
  dragging = true
  lastX = e.clientX
  lastY = e.clientY
}

function onMove(e) {
  if (!wrap.value) return
  const rect = wrap.value.getBoundingClientRect()
  const nx = (e.clientX - rect.left) / rect.width - 0.5
  const ny = (e.clientY - rect.top) / rect.height - 0.5
  if (dragging) {
    rotY += (e.clientX - lastX) * 0.005
    rotX = Math.max(-0.3, Math.min(0.5, rotX + (e.clientY - lastY) * 0.003))
    lastX = e.clientX
    lastY = e.clientY
  } else {
    // 轻微视差，免拖拽也能看清车体
    tiltX = ny * 0.1
    tiltZ = nx * 0.16
  }
}

function onUp() {
  dragging = false
}

function animate(now) {
  if (disposed) return
  rafId = requestAnimationFrame(animate)
  const t = (now || 0) / 1000
  if (!dragging) rotY += 0.0012
  if (carGroup) {
    carGroup.rotation.y += (rotY + tiltZ - carGroup.rotation.y) * 0.06
    carGroup.rotation.x += ((rotX + tiltX) - carGroup.rotation.x) * 0.05
    carGroup.position.y = 0.35 + Math.sin(t * 1.05) * 0.04
  }
  if (globeGroup) globeGroup.rotation.y += 0.0007
  // 扫描光带周期扫过
  if (scanBand) {
    const k = (t % 4.5) / 4.5
    scanBand.position.x = -3 + k * 5
    scanBand.material.opacity = k < 0.15 ? k / 0.15 * 0.22 : (k > 0.8 ? (1 - k) / 0.2 * 0.22 : 0.22)
  }
  // 数据流沿曲线流动
  if (streamDots && streamCurve) {
    const attr = streamDots.geometry.getAttribute('position')
    const n = attr.count
    for (let i = 0; i < n; i++) {
      const k = (t * 0.06 + i / n) % 1
      const p = streamCurve.getPoint(k)
      attr.setXYZ(i, p.x, p.y, p.z)
    }
    attr.needsUpdate = true
  }
  for (const ring of rings) {
    const k = ((t * 0.26 + ring.userData.phase) % 1)
    const s = 0.8 + k * 3.4
    ring.scale.set(s, s, s)
    ring.material.opacity = (1 - k) * 0.35
  }
  if (radar) radar.rotation.z = t * 0.5
  if (renderer && scene && camera) renderer.render(scene, camera)
  if (carGroup) window.__vizRotY = carGroup.rotation.y
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
    camera.position.set(3.4, 1.9, 6.4)
    camera.lookAt(-0.5, 0.85, 0)
    renderer = new THREE.WebGLRenderer({ canvas: cv.value, antialias: true, alpha: true })
    renderer.setClearColor(0x000000, 0)
    renderer.setPixelRatio(Math.min(window.devicePixelRatio || 1, 2))
    buildEnv()
    buildScanBand()
    buildStream()
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
.viz-scene { position: relative; width: 100%; height: 100%; cursor: grab; touch-action: none; }
.viz-scene:active { cursor: grabbing; }
.viz-scene canvas { width: 100%; height: 100%; display: block; }
</style>

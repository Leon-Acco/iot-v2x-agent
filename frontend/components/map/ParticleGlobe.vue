<template>
  <!-- 粒子地球：点云球体 + 轨道环 + 车辆标记，鼠标跟随 -->
  <div ref="wrap" class="globe-wrap">
    <canvas ref="cv"></canvas>
  </div>
</template>

<script setup>
import * as THREE from 'three'
const props = defineProps({
  vehicles: { type: Array, default: () => [] },
  // 配色（可选）：deep 主色 / mid 中间色 / base 底色，hex 字符串；默认蓝色系
  palette: { type: Object, default: null }
})

const wrap = ref(null)
const cv = ref(null)
let renderer = null
let scene = null
let camera = null
let globe = null
let markers = null
let markerPositions = null
let rings = []
let rafId = null
let mouseX = 0
let mouseY = 0
let rotX = 0
let rotY = 0
let disposed = false

const R = 1.6


// 软圆粒子贴图
function makeSprite() {
  const c = document.createElement('canvas')
  c.width = 64; c.height = 64
  const ctx = c.getContext('2d')
  const g = ctx.createRadialGradient(32, 32, 0, 32, 32, 32)
  g.addColorStop(0, 'rgba(255,255,255,1)')
  g.addColorStop(0.4, 'rgba(255,255,255,.85)')
  g.addColorStop(1, 'rgba(255,255,255,0)')
  ctx.fillStyle = g
  ctx.fillRect(0, 0, 64, 64)
  return new THREE.CanvasTexture(c)
}

function latLngToVec3(lat, lng, r) {
  const phi = (90 - lat) * Math.PI / 180
  const theta = (lng + 180) * Math.PI / 180
  return new THREE.Vector3(
    -r * Math.sin(phi) * Math.cos(theta),
    r * Math.cos(phi),
    r * Math.sin(phi) * Math.sin(theta)
  )
}

// 解析页面主题配色（默认蓝色系）
function pal() {
  const p = props.palette || { deep: '#0f8a6a', mid: '#8fd3bc', base: '#B9CDE8' }
  return {
    deep: new THREE.Color(p.deep),
    mid: new THREE.Color(p.mid),
    base: new THREE.Color(p.base)
  }
}

function buildGlobe() {
  const THREE = THREE
  const COUNT = 14000
  const pos = new Float32Array(COUNT * 3)
  const col = new Float32Array(COUNT * 3)
  const P = pal()
  const base = P.base
  const bright = P.deep
  const mid = P.mid
  const tmp = new THREE.Color()
  for (let i = 0; i < COUNT; i++) {
    const y = 1 - (i / (COUNT - 1)) * 2
    const radius = Math.sqrt(1 - y * y)
    const theta = i * 2.399963229728653
    pos[i * 3] = Math.cos(theta) * radius * R
    pos[i * 3 + 1] = y * R
    pos[i * 3 + 2] = Math.sin(theta) * radius * R
    // 稀疏亮点打破单调
    const sparkle = Math.random() < 0.035
    tmp.copy(sparkle ? bright : (Math.random() < 0.35 ? mid : base))
    col[i * 3] = tmp.r
    col[i * 3 + 1] = tmp.g
    col[i * 3 + 2] = tmp.b
  }
  const geo = new THREE.BufferGeometry()
  geo.setAttribute('position', new THREE.BufferAttribute(pos, 3))
  geo.setAttribute('color', new THREE.BufferAttribute(col, 3))
  const mat = new THREE.PointsMaterial({
    size: 0.02, vertexColors: true, map: makeSprite(),
    transparent: true, opacity: 0.85, depthWrite: false, sizeAttenuation: true
  })
  globe = new THREE.Points(geo, mat)
  scene.add(globe)
}

function buildRings() {
  const THREE = THREE
  const P = pal()
  const hexNum = (c) => (Math.round(c.r * 255) << 16) + (Math.round(c.g * 255) << 8) + Math.round(c.b * 255)
  const defs = [
    { r: R * 1.35, tiltX: 0.5, tiltZ: 0.15, color: hexNum(P.mid), opacity: 0.35 },
    { r: R * 1.6, tiltX: 1.05, tiltZ: -0.2, color: hexNum(P.base), opacity: 0.28 },
    { r: R * 1.85, tiltX: 0.25, tiltZ: 0.45, color: hexNum(P.deep), opacity: 0.16 }
  ]
  for (const d of defs) {
    const curve = new THREE.EllipseCurve(0, 0, d.r, d.r, 0, Math.PI * 2, false, 0)
    const pts = curve.getPoints(160)
    const geo = new THREE.BufferGeometry().setFromPoints(pts)
    const mat = new THREE.LineBasicMaterial({ color: d.color, transparent: true, opacity: d.opacity })
    const ring = new THREE.LineLoop(geo, mat)
    ring.rotation.x = d.tiltX
    ring.rotation.z = d.tiltZ
    rings.push(ring)
    scene.add(ring)
  }
}

function buildMarkers() {
  const THREE = THREE
  const list = (props.vehicles || []).filter(v => typeof v.lng === 'number' && typeof v.lat === 'number').slice(0, 400)
  if (!list.length) return
  markerPositions = new Float32Array(list.length * 3)
  const col = new Float32Array(list.length * 3)
  const P = pal()
  const online = P.deep
  const offline = new THREE.Color(0x94A3B8)
  const tmp = new THREE.Color()
  list.forEach((v, i) => {
    const p = latLngToVec3(v.lat, v.lng, R * 1.02)
    markerPositions[i * 3] = p.x
    markerPositions[i * 3 + 1] = p.y
    markerPositions[i * 3 + 2] = p.z
    tmp.copy(Number(v.online) === 1 ? online : offline)
    col[i * 3] = tmp.r
    col[i * 3 + 1] = tmp.g
    col[i * 3 + 2] = tmp.b
  })
  const geo = new THREE.BufferGeometry()
  geo.setAttribute('position', new THREE.BufferAttribute(markerPositions, 3))
  geo.setAttribute('color', new THREE.BufferAttribute(col, 3))
  const mat = new THREE.PointsMaterial({
    size: 0.055, vertexColors: true, map: makeSprite(),
    transparent: true, opacity: 0.95, depthWrite: false
  })
  if (markers) scene.remove(markers)
  markers = new THREE.Points(geo, mat)
  globe.add(markers)
}

function animate(t) {
  if (disposed) return
  rafId = requestAnimationFrame(animate)
  // 鼠标跟随缓动
  rotY += ((mouseX * 0.5) - rotY) * 0.04
  rotX += ((mouseY * 0.3) - rotX) * 0.04
  if (globe) {
    globe.rotation.y += 0.0012 + rotY * 0.0006
    globe.rotation.x = rotX * 0.4
  }
  rings.forEach((ring, i) => { ring.rotation.y += 0.0006 * (i + 1) })
  if (renderer && scene && camera) renderer.render(scene, camera)
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

function onMouseMove(e) {
  if (!wrap.value) return
  const rect = wrap.value.getBoundingClientRect()
  mouseX = ((e.clientX - rect.left) / rect.width - 0.5) * 2
  mouseY = ((e.clientY - rect.top) / rect.height - 0.5) * 2
}

onMounted(async () => {
  try {
    
    const THREE = THREE
    scene = new THREE.Scene()
    camera = new THREE.PerspectiveCamera(42, 1, 0.1, 100)
    camera.position.set(0, 0.4, 5.6)
    renderer = new THREE.WebGLRenderer({ canvas: cv.value, antialias: true, alpha: true })
    renderer.setClearColor(0x000000, 0)
    const dpr = Math.min(window.devicePixelRatio || 1, 2)
    renderer.setPixelRatio(dpr)
    buildGlobe()
    buildRings()
    buildMarkers()
    onResize()
    window.addEventListener('resize', onResize)
    window.addEventListener('pointermove', onMouseMove)
    animate()
  } catch (e) { /* WebGL 不可用时静态 */ }
})

watch(() => props.vehicles, () => { if (globe) buildMarkers() })

onBeforeUnmount(() => {
  disposed = true
  if (rafId) cancelAnimationFrame(rafId)
  window.removeEventListener('resize', onResize)
  window.removeEventListener('pointermove', onMouseMove)
  if (renderer) {
    renderer.dispose()
    renderer.forceContextLoss && renderer.forceContextLoss()
    renderer = null
  }
})
</script>

<style scoped>
.globe-wrap { position: relative; width: 100%; height: 100%; }
.globe-wrap canvas { width: 100%; height: 100%; display: block; }
</style>

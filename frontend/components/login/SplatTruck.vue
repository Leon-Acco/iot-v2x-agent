<template>
  <!-- Gaussian splatting holo truck: image -> pixel sampling -> 2.5D splat cloud -->
  <!-- 特效层：地面双环 + 倾斜轨道环 + 环境尘粒（加色辉光） -->
  <div ref="wrap" class="splat-truck">
    <canvas ref="cv" :class="{ show: ready }" @pointerdown="onPulse"></canvas>
    <div v-if="!ready" class="veil">
      <div class="bar"><i></i></div>
      <p>INITIALIZING SPLATS</p>
    </div>
  </div>
</template>

<script setup>
// 2.5D Gaussian Splatting holo deck, ported from E:/dst_agent_car reference
// 图片 → 采样为 splat 点云 → 点精灵 + 高斯衰减 → α 混合
const props = defineProps({
  src: { type: String, default: '/images/splat-truck.png' },
  density: { type: Number, default: 2 },   // sampling step in px, smaller = denser
  size: { type: Number, default: 2.4 },    // splat radius factor
  depth: { type: Number, default: 1.0 },   // z-depth factor
  drift: { type: Number, default: 1.0 },   // breathing drift amplitude
  shiftX: { type: Number, default: 0 },    // scene offset x, fraction of view width (negative = left)
  shiftY: { type: Number, default: 0 },    // scene offset y, fraction of view height (positive = up)
  autoSpin: { type: Boolean, default: true },
  theme: { type: String, default: 'dark' }   // light: transparent canvas + deeper teal fx
})
const emit = defineEmits(['ready'])

const wrap = ref(null)
const cv = ref(null)
const ready = ref(false)

let gl = null
let prog = null
let A = null
let U = null
let imgBufs = null
let fxBufs = null
let splatCount = 0
let fxCount = 0
let truckW = 0
let truckH = 0
let truckScale = 1
let rafId = null
let disposed = false
let ro = null

// 交互状态
const mouse = { x: 0, y: 0, tx: 0, ty: 0, k: 0, tk: 0 }
const rot = { yaw: 0, pitch: 0 }
let pulse = 0
let scatter = 1.0           // start scattered, assemble on load
const scatterTarget = 0

// ---- shaders ----
const VERT = `
precision highp float;

attribute vec3  a_home;    // 归属位置（图像层）；特效层为 (R, baseY, ang0)
attribute vec4  a_color;
attribute float a_size;
attribute float a_seed;
attribute float a_kind;    // 0=图像 1=地面环 2=轨道环 3=尘粒

uniform vec2  u_res;
uniform vec2  u_shift;   // scene offset in pixels
uniform float u_time;
uniform mat3  u_rot;
uniform vec2  u_mouse;
uniform float u_mouseK;
uniform float u_scatter;
uniform float u_depth;
uniform float u_drift;
uniform float u_sizeK;
uniform float u_dpr;

varying vec4 v_color;

float hash(float n) { return fract(sin(n) * 43758.5453123); }

void main() {
  vec3 p = a_home;
  float twinkle = 1.0;

  if (a_kind > 0.5 && a_kind < 1.5) {
    // ── 地面椭圆环：粒子沿环流动，双环反向 ──
    float dir = a_seed < 0.5 ? 1.0 : -1.0;
    float ang = a_home.z + u_time * 0.30 * dir;
    float R = a_home.x;
    p = vec3(cos(ang) * R, a_home.y + sin(ang) * R * 0.285, 40.0);
    // 流动明暗：虚线辉光
    twinkle = 0.35 + 0.65 * pow(0.5 + 0.5 * sin(ang * 16.0 - u_time * 2.2 * dir), 2.0);
  } else if (a_kind > 1.5 && a_kind < 2.5) {
    // ── 倾斜轨道环：绕车缓转 ──
    float ang = a_home.z - u_time * 0.16;
    float R = a_home.x;
    vec3 c = vec3(cos(ang), 0.0, sin(ang)) * R;
    p = vec3(c.x, a_home.y + c.z * 0.44, c.z * 0.90);
    twinkle = 0.45 + 0.55 * pow(0.5 + 0.5 * sin(ang * 9.0 + u_time * 1.4), 2.0);
  } else {
    // ── 图像层 / 尘粒：呼吸漂浮 ──
    float dk = a_kind > 2.5 ? 3.2 : 1.0;   // 尘粒漂得更开
    p.z *= u_depth;
    float t  = u_time * 0.55 + a_seed * 6.28318;
    float t2 = u_time * 0.31 + hash(a_seed * 7.0) * 6.28318;
    p.x += (sin(t * 0.9 + a_home.y * 0.006) + 0.5 * sin(t2 * 1.7)) * 1.6 * u_drift * dk;
    p.y += (cos(t * 0.7 + a_home.x * 0.005) + 0.5 * cos(t2 * 1.3)) * 1.3 * u_drift * dk;
    p.z += sin(t * 0.5 + a_seed * 12.0) * 3.0 * u_drift * u_depth * dk;

    // 散开仅作用于图像层
    float sr = u_scatter * (a_kind < 0.5 ? 1.0 : 0.0);
    if (sr > 0.001) {
      vec3 dir = normalize(vec3(
        hash(a_seed * 3.1) - 0.5,
        hash(a_seed * 5.7) - 0.5,
        hash(a_seed * 9.3) - 0.5));
      float fly = sr * (140.0 + hash(a_seed * 11.0) * 620.0);
      p += dir * fly;
    }
    // 尘粒闪烁
    if (a_kind > 2.5) twinkle = 0.4 + 0.6 * (0.5 + 0.5 * sin(u_time * 1.8 + a_seed * 40.0));
  }

  // 鼠标斥力：图像层与尘粒响应，环不受影响
  if (u_mouseK > 0.001 && (a_kind < 0.5 || a_kind > 2.5)) {
    vec2 dm = p.xy - u_mouse;
    float md2 = dot(dm, dm);
    float f = exp(-md2 / 16900.0) * u_mouseK;
    p.xy += (dm / max(length(dm), 1.0)) * f;
    p.z  += f * 0.6;
  }

  p.xy += u_shift;
  p = u_rot * p;

  const float DIST = 1050.0;
  float persp = DIST / max(DIST - p.z, 60.0);

  vec2 ndc = vec2(p.x * persp / (u_res.x * 0.5), p.y * persp / (u_res.y * 0.5));
  gl_Position = vec4(ndc, 0.0, 1.0);
  gl_PointSize = a_size * u_sizeK * persp * u_dpr;

  float fade = clamp(1.0 - (p.z + 300.0) / 3000.0, 0.55, 1.0);
  v_color = vec4(a_color.rgb, a_color.a * fade * twinkle);
}
`

const FRAG = `
precision highp float;
uniform float u_time;
varying vec4 v_color;

void main() {
  vec2  uv = gl_PointCoord - 0.5;
  float r2 = dot(uv, uv) * 4.0;
  float g  = exp(-r2 * 2.9);                 // σ ≈ 0.42
  float a  = g * v_color.a;
  // 全息扫描纹理
  a *= 0.92 + 0.08 * sin(gl_FragCoord.y * 1.4 - u_time * 3.0);
  if (a < 0.008) discard;
  gl_FragColor = vec4(v_color.rgb, a);
}
`

function compile(type, src) {
  const s = gl.createShader(type)
  gl.shaderSource(s, src)
  gl.compileShader(s)
  if (!gl.getShaderParameter(s, gl.COMPILE_STATUS)) throw new Error(gl.getShaderInfoLog(s))
  return s
}

// 通用：上传一组 splat 缓冲
function makeVBO(set) {
  const make = (loc, arr, comps) => {
    const buf = gl.createBuffer()
    gl.bindBuffer(gl.ARRAY_BUFFER, buf)
    gl.bufferData(gl.ARRAY_BUFFER, arr, gl.STATIC_DRAW)
    return { buf, loc, comps }
  }
  return {
    home: make(A.home, set.home, 3),
    color: make(A.color, set.color, 4),
    size: make(A.size, set.size, 1),
    seed: make(A.seed, set.seed, 1),
    kind: set.kind ? make(A.kind, set.kind, 1) : null
  }
}

function bindVBO(bufs) {
  for (const b of Object.values(bufs)) {
    if (!b) continue
    gl.bindBuffer(gl.ARRAY_BUFFER, b.buf)
    gl.enableVertexAttribArray(b.loc)
    gl.vertexAttribPointer(b.loc, b.comps, gl.FLOAT, false, 0, 0)
  }
  if (!bufs.kind) {
    gl.disableVertexAttribArray(A.kind)
    gl.vertexAttrib1f(A.kind, 0.0)
  }
}

function dropVBO(bufs) {
  if (bufs) for (const b of Object.values(bufs)) if (b) gl.deleteBuffer(b.buf)
}

// 图片采样 → 图像层 splat (sorted far-to-near, painter algorithm)
function buildSplats(img, step, vw, vh) {
  const w = img.width
  const h = img.height
  const off = document.createElement('canvas')
  off.width = w
  off.height = h
  const ctx = off.getContext('2d', { willReadFrequently: true })
  ctx.drawImage(img, 0, 0)
  const data = ctx.getImageData(0, 0, w, h).data

  let hasAlpha = false
  for (let i = 3; i < data.length; i += 16) if (data[i] < 250) { hasAlpha = true; break }

  const keep = (i) => {
    if (hasAlpha) return data[i + 3] > 140
    const r = data[i]; const g = data[i + 1]; const b = data[i + 2]
    const lum = (r * 0.2126 + g * 0.7152 + b * 0.0722) / 255
    const sat = (Math.max(r, g, b) - Math.min(r, g, b)) / 255
    return lum < 0.90 || sat > 0.13
  }

  let n = 0
  for (let y = 0; y < h; y += step)
    for (let x = 0; x < w; x += step)
      if (keep((y * w + x) * 4)) n++

  const home = new Float32Array(n * 3)
  const color = new Float32Array(n * 4)
  const size = new Float32Array(n)
  const seed = new Float32Array(n)

  truckW = w
  truckH = h
  truckScale = Math.min((vw * 0.62) / w, (vh * 0.72) / h)
  const scale = truckScale

  let k = 0
  for (let y = 0; y < h; y += step) {
    for (let x = 0; x < w; x += step) {
      const i = (y * w + x) * 4
      if (!keep(i)) continue
      const r = data[i]; const g = data[i + 1]; const b = data[i + 2]
      const lum = (r * 0.2126 + g * 0.7152 + b * 0.0722) / 255
      const sat = (Math.max(r, g, b) - Math.min(r, g, b)) / 255

      const jx = (Math.random() - 0.5) * step * 0.9
      const jy = (Math.random() - 0.5) * step * 0.9

      home[k * 3] = (x + jx - w / 2) * scale
      home[k * 3 + 1] = (h / 2 - y - jy) * scale
      home[k * 3 + 2] = (0.5 - lum) * 260.0 + (Math.random() - 0.5) * 24.0

      color[k * 4] = r / 255
      color[k * 4 + 1] = g / 255
      color[k * 4 + 2] = b / 255
      let alpha = Math.min(1, 0.84 + (1 - lum) * 0.25 + sat * 0.20)
      if (hasAlpha) {
        alpha *= Math.min(1, data[i + 3] / 200)
      } else {
        const fx = Math.min(x, w - x) / w
        const fy = Math.min(y, h - y) / h
        alpha *= Math.min(1, Math.min(fx, fy) / 0.035)
      }
      color[k * 4 + 3] = alpha

      size[k] = step * scale * (1.8 + Math.random() * 0.5)
      seed[k] = Math.random()
      k++
    }
  }

  const order = Array.from({ length: n }, (_, i) => i)
  order.sort((a2, b2) => home[b2 * 3 + 2] - home[a2 * 3 + 2])

  const sHome = new Float32Array(n * 3)
  const sColor = new Float32Array(n * 4)
  const sSize = new Float32Array(n)
  const sSeed = new Float32Array(n)
  for (let m = 0; m < n; m++) {
    const s = order[m]
    sHome.set(home.subarray(s * 3, s * 3 + 3), m * 3)
    sColor.set(color.subarray(s * 4, s * 4 + 4), m * 4)
    sSize[m] = size[s]
    sSeed[m] = seed[s]
  }
  return { n, home: sHome, color: sColor, size: sSize, seed: sSeed }
}

// 程序化特效层：地面双环 + 轨道环 + 尘粒
function buildFx() {
  const lightFx = props.theme === 'light'
  const C_GROUND = lightFx ? [0.10, 0.55, 0.50] : [0.30, 0.95, 0.88]
  const C_ORBIT = lightFx ? [0.12, 0.60, 0.55] : [0.42, 0.98, 0.92]
  const C_DUST = lightFx ? [0.16, 0.58, 0.54] : [0.45, 0.95, 0.90]
  const halfW = (truckW * truckScale) / 2
  const halfH = (truckH * truckScale) / 2
  const baseY = -halfH * 0.94
  const R1 = halfW * 0.86
  const R2 = halfW * 0.62
  const RO = halfW * 0.78

  const N_GROUND = 900
  const N_ORBIT = 520
  const N_DUST = 850
  const n = N_GROUND + N_ORBIT + N_DUST

  const home = new Float32Array(n * 3)
  const color = new Float32Array(n * 4)
  const size = new Float32Array(n)
  const seed = new Float32Array(n)
  const kind = new Float32Array(n)

  let k = 0
  const push = (hx, hy, hz, r, g, b, a, sz, kd) => {
    home[k * 3] = hx; home[k * 3 + 1] = hy; home[k * 3 + 2] = hz
    color[k * 4] = r; color[k * 4 + 1] = g; color[k * 4 + 2] = b; color[k * 4 + 3] = a
    size[k] = sz; seed[k] = Math.random(); kind[k] = kd
    k++
  }

  for (let i = 0; i < N_GROUND; i++) {
    const inner = i < N_GROUND * 0.55
    const R = (inner ? R2 : R1) * (0.985 + Math.random() * 0.03)
    push(R, baseY, Math.random() * Math.PI * 2,
      C_GROUND[0], C_GROUND[1], C_GROUND[2], inner ? 0.34 : 0.5,
      (inner ? 2.2 : 2.8) + Math.random() * 1.4, 1)
    if (inner) seed[k - 1] = Math.random() * 0.5
    else seed[k - 1] = 0.5 + Math.random() * 0.5
  }
  for (let i = 0; i < N_ORBIT; i++) {
    const R = RO * (0.99 + Math.random() * 0.02)
    push(R, halfH * 0.10, Math.random() * Math.PI * 2,
      C_ORBIT[0], C_ORBIT[1], C_ORBIT[2], 0.42,
      1.6 + Math.random() * 1.2, 2)
  }
  for (let i = 0; i < N_DUST; i++) {
    push((Math.random() - 0.5) * halfW * 2.6,
      (Math.random() - 0.5) * halfH * 2.6,
      (Math.random() - 0.5) * 500,
      C_DUST[0], C_DUST[1], C_DUST[2], 0.10 + Math.random() * 0.22,
      1.2 + Math.random() * 2.0, 3)
  }
  return { n, home, color, size, seed, kind }
}

function onPulse() { pulse = 1.0 }

function onPointerMove(e) {
  if (!wrap.value) return
  const r = wrap.value.getBoundingClientRect()
  mouse.tx = e.clientX - r.left - r.width / 2
  mouse.ty = r.height / 2 - (e.clientY - r.top)
  mouse.tk = 26
}
function onPointerLeave() { mouse.tk = 0 }

// 主循环
let lastT = 0
function frame(now) {
  if (disposed) return
  lastT = now
  const t = now / 1000
  const rect = wrap.value.getBoundingClientRect()
  const vw = rect.width
  const vh = rect.height

  scatter += (scatterTarget - scatter) * 0.045
  pulse *= 0.94
  const sEff = Math.min(scatter + pulse * 0.85, 1.4)

  mouse.x += (mouse.tx - mouse.x) * 0.06
  mouse.y += (mouse.ty - mouse.y) * 0.06
  mouse.k += (mouse.tk - mouse.k) * 0.08

  const targetYaw = props.autoSpin ? Math.sin(t * 0.22) * 0.42 : (mouse.x / vw) * 0.34
  const targetPitch = (mouse.y / vh) * -0.22
  rot.yaw += (targetYaw - rot.yaw) * 0.05
  rot.pitch += (targetPitch - rot.pitch) * 0.05

  const cy = Math.cos(rot.yaw); const sy = Math.sin(rot.yaw)
  const cp = Math.cos(rot.pitch); const sp = Math.sin(rot.pitch)
  const m = new Float32Array([
    cy, 0, -sy,
    sy * sp, cp, cy * sp,
    sy * cp, -sp, cy * cp
  ])

  gl.clear(gl.COLOR_BUFFER_BIT)
  gl.uniform1f(U.u_time, t)
  gl.uniformMatrix3fv(U.u_rot, false, m)
  // mouse in truck-local space (undo scene shift so repulsion tracks the truck)
  gl.uniform2f(U.u_mouse, mouse.x - props.shiftX * vw, mouse.y - props.shiftY * vh)
  gl.uniform1f(U.u_mouseK, mouse.k)
  gl.uniform1f(U.u_scatter, sEff)
  gl.uniform1f(U.u_depth, props.depth)
  gl.uniform1f(U.u_drift, props.drift)
  gl.uniform1f(U.u_sizeK, props.size / 2.4)
  gl.uniform2f(U.u_shift, props.shiftX * vw, props.shiftY * vh)

  // image layer: normal alpha blend; fx layer: additive glow (dark theme only)
  gl.blendFunc(gl.SRC_ALPHA, gl.ONE_MINUS_SRC_ALPHA)
  if (imgBufs) { bindVBO(imgBufs); gl.drawArrays(gl.POINTS, 0, splatCount) }
  if (props.theme !== 'light') gl.blendFunc(gl.SRC_ALPHA, gl.ONE)
  if (fxBufs) { bindVBO(fxBufs); gl.drawArrays(gl.POINTS, 0, fxCount) }

  rafId = requestAnimationFrame(frame)
}

function resize() {
  if (!gl || !wrap.value) return
  const rect = wrap.value.getBoundingClientRect()
  const dpr = Math.min(window.devicePixelRatio || 1, 2)
  cv.value.width = Math.max(1, rect.width * dpr)
  cv.value.height = Math.max(1, rect.height * dpr)
  gl.viewport(0, 0, cv.value.width, cv.value.height)
  gl.uniform2f(U.u_res, rect.width, rect.height)
  gl.uniform1f(U.u_dpr, dpr)
}

let srcImg = null
function rebuild() {
  if (!srcImg || !wrap.value) return
  const rect = wrap.value.getBoundingClientRect()
  dropVBO(imgBufs); dropVBO(fxBufs)
  const s = buildSplats(srcImg, props.density, rect.width, rect.height)
  imgBufs = makeVBO(s)
  splatCount = s.n
  const fx = buildFx()
  fxBufs = makeVBO(fx)
  fxCount = fx.n
}

let resizeTimer = 0
function onResize() {
  resize()
  clearTimeout(resizeTimer)
  resizeTimer = setTimeout(rebuild, 260)
}

onMounted(() => {
  const canvas = cv.value
  gl = canvas.getContext('webgl', { alpha: true, antialias: false, powerPreference: 'high-performance' })
  if (!gl) {
    ready.value = true
    emit('ready')
    return
  }

  prog = gl.createProgram()
  gl.attachShader(prog, compile(gl.VERTEX_SHADER, VERT))
  gl.attachShader(prog, compile(gl.FRAGMENT_SHADER, FRAG))
  gl.linkProgram(prog)
  if (!gl.getProgramParameter(prog, gl.LINK_STATUS)) throw new Error(gl.getProgramInfoLog(prog))
  gl.useProgram(prog)

  A = {
    home: gl.getAttribLocation(prog, 'a_home'),
    color: gl.getAttribLocation(prog, 'a_color'),
    size: gl.getAttribLocation(prog, 'a_size'),
    seed: gl.getAttribLocation(prog, 'a_seed'),
    kind: gl.getAttribLocation(prog, 'a_kind')
  }
  U = {}
  for (const n of ['u_res', 'u_shift', 'u_time', 'u_rot', 'u_mouse', 'u_mouseK', 'u_scatter', 'u_depth', 'u_drift', 'u_sizeK', 'u_dpr'])
    U[n] = gl.getUniformLocation(prog, n)

  gl.disable(gl.DEPTH_TEST)
  if (props.theme === 'light') gl.clearColor(0, 0, 0, 0)   // transparent over light page background
  else gl.clearColor(0.008, 0.075, 0.086, 1.0)   // deep tech background

  window.addEventListener('pointermove', onPointerMove)
  window.addEventListener('pointerleave', onPointerLeave)
  window.addEventListener('resize', onResize)
  if (window.ResizeObserver) {
    ro = new ResizeObserver(onResize)
    ro.observe(wrap.value)
  }

  const img = new Image()
  img.onload = () => {
    if (disposed) return
    srcImg = img
    resize()
    rebuild()
    lastT = performance.now()
    rafId = requestAnimationFrame(frame)
    setTimeout(() => { ready.value = true; emit('ready') }, 350)
  }
  img.onerror = () => { ready.value = true; emit('ready') }
  img.src = props.src
})

onBeforeUnmount(() => {
  disposed = true
  if (rafId) cancelAnimationFrame(rafId)
  clearTimeout(resizeTimer)
  window.removeEventListener('pointermove', onPointerMove)
  window.removeEventListener('pointerleave', onPointerLeave)
  window.removeEventListener('resize', onResize)
  if (ro) ro.disconnect()
  dropVBO(imgBufs); dropVBO(fxBufs)
  if (gl) {
    const ext = gl.getExtension('WEBGL_lose_context')
    if (ext) ext.loseContext()
  }
})
</script>

<style scoped>
.splat-truck { position: absolute; inset: 0; overflow: hidden; }
.splat-truck canvas {
  position: absolute; inset: 0; width: 100%; height: 100%;
  display: block; cursor: crosshair;
  opacity: 0;
  transition: opacity .8s ease;
}
.splat-truck canvas.show { opacity: 1; }
/* loading veil */
.veil {
  position: absolute; inset: 0; z-index: 5;
  display: flex; align-items: center; justify-content: center;
  flex-direction: column; gap: 18px; pointer-events: none;
}
.veil .bar { width: 200px; height: 2px; background: rgba(64, 232, 220, .14); overflow: hidden; }
.veil .bar i {
  display: block; height: 100%; width: 40%;
  background: #40e8dc; box-shadow: 0 0 10px #40e8dc;
  animation: scan 1.1s ease-in-out infinite alternate;
}
@keyframes scan { from { transform: translateX(-60%); } to { transform: translateX(320%); } }
.veil p {
  margin: 0; font-size: 11px; letter-spacing: .34em; color: #7fc9c6;
  font-family: ui-monospace, "SF Mono", "JetBrains Mono", Menlo, Consolas, monospace;
}
</style>

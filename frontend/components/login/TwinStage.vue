<template>
  <!-- H3 登录舞台：原生 WebGL 线框厢式货车（细节版）+ 扫描平面 + 地面网格下方 0.5 倒置孪生镜像
     + 量程环/刻度/四角括弧舞台层 + 远处待检车。代码照抄 login-preview-h3-twin3d-detail.html，
     仅做 Vue 工程化（模板 ref / 生命周期清理 / ResizeObserver），相机常量一字不改。 -->
  <canvas ref="stageEl" class="twin-stage" aria-label="3D 线框细节货车与其孪生镜像（浅色），可拖拽旋转"></canvas>
</template>

<script setup>
const stageEl = ref(null)
let dispose = null

onMounted(() => {
  const cv = stageEl.value
  var gl = null
  try { gl = cv.getContext('webgl', { antialias: true, alpha: true }) || cv.getContext('experimental-webgl') } catch (e) {}
  if (!gl) return

  function mMul(a, b) {
    var o = new Array(16)
    for (var c = 0; c < 4; c++) for (var r = 0; r < 4; r++)
      o[c * 4 + r] = a[r] * b[c * 4] + a[4 + r] * b[c * 4 + 1] + a[8 + r] * b[c * 4 + 2] + a[12 + r] * b[c * 4 + 3]
    return o
  }
  function mPersp(fov, asp, n, f) {
    var t = 1 / Math.tan(fov / 2), nf = 1 / (n - f)
    return [t / asp, 0, 0, 0, 0, t, 0, 0, 0, 0, (f + n) * nf, -1, 0, 0, 2 * f * n * nf, 0]
  }
  function mRotX(a) { var c = Math.cos(a), s = Math.sin(a); return [1, 0, 0, 0, 0, c, s, 0, 0, -s, c, 0, 0, 0, 0, 1] }
  function mRotY(a) { var c = Math.cos(a), s = Math.sin(a); return [c, 0, -s, 0, 0, 1, 0, 0, s, 0, c, 0, 0, 0, 0, 1] }
  function mScale(x, y, z) { return [x, 0, 0, 0, 0, y, 0, 0, 0, 0, z, 0, 0, 0, 0, 1] }
  function mTrans(x, y, z) { return [1, 0, 0, 0, 0, 1, 0, 0, 0, 0, 1, 0, x, y, z, 1] }

  function sh(type, src) { var s = gl.createShader(type); gl.shaderSource(s, src); gl.compileShader(s); return s }
  function prog(vs, fs) {
    var p = gl.createProgram()
    gl.attachShader(p, sh(gl.VERTEX_SHADER, vs)); gl.attachShader(p, sh(gl.FRAGMENT_SHADER, fs))
    gl.linkProgram(p); return p
  }
  // 线着色器
  var PL = prog(
    'attribute vec3 aPos;attribute vec4 aCol;uniform mat4 uMVP;varying vec4 vC;' +
    'void main(){gl_Position=uMVP*vec4(aPos,1.0);vC=aCol;}',
    'precision mediump float;varying vec4 vC;uniform float uAlpha;' +
    'void main(){gl_FragColor=vec4(vC.rgb*vC.a*uAlpha,vC.a*uAlpha);}'
  )
  // 三角形着色器（扫描面）
  var PT = prog(
    'attribute vec3 aPos;attribute vec4 aCol;uniform mat4 uMVP;varying vec4 vC;' +
    'void main(){gl_Position=uMVP*vec4(aPos,1.0);vC=aCol;}',
    'precision mediump float;varying vec4 vC;' +
    'void main(){gl_FragColor=vec4(vC.rgb*vC.a,vC.a);}'
  )
  // 点着色器
  var PP = prog(
    'attribute vec3 aPos;attribute vec3 aCol;attribute vec2 aSA;' +
    'uniform mat4 uMVP;uniform float uScale;varying vec3 vC;varying float vA;' +
    'void main(){vec4 p=uMVP*vec4(aPos,1.0);gl_Position=p;' +
    'gl_PointSize=clamp(aSA.x*uScale/max(p.w,0.001),1.0,22.0);vC=aCol;vA=aSA.y;}',
    'precision mediump float;varying vec3 vC;varying float vA;' +
    'void main(){vec2 d=gl_PointCoord-vec2(0.5);float r=length(d)*2.0;' +
    'float a=smoothstep(1.0,0.5,r)*vA;if(a<0.02)discard;gl_FragColor=vec4(vC*a,a);}'
  )
  var ln = { aPos: gl.getAttribLocation(PL, 'aPos'), aCol: gl.getAttribLocation(PL, 'aCol') }
  var lnU = { uMVP: gl.getUniformLocation(PL, 'uMVP'), uAlpha: gl.getUniformLocation(PL, 'uAlpha') }
  var tr = { aPos: gl.getAttribLocation(PT, 'aPos'), aCol: gl.getAttribLocation(PT, 'aCol') }
  var trU = { uMVP: gl.getUniformLocation(PT, 'uMVP') }
  var pt = { aPos: gl.getAttribLocation(PP, 'aPos'), aCol: gl.getAttribLocation(PP, 'aCol'), aSA: gl.getAttribLocation(PP, 'aSA') }
  var ptU = { uMVP: gl.getUniformLocation(PP, 'uMVP'), uScale: gl.getUniformLocation(PP, 'uScale') }

  function buf(data, dyn) {
    var b = gl.createBuffer()
    gl.bindBuffer(gl.ARRAY_BUFFER, b)
    gl.bufferData(gl.ARRAY_BUFFER, data, dyn ? gl.DYNAMIC_DRAW : gl.STATIC_DRAW)
    return b
  }
  function bindLns(b) {
    gl.bindBuffer(gl.ARRAY_BUFFER, b)
    gl.enableVertexAttribArray(ln.aPos); gl.vertexAttribPointer(ln.aPos, 3, gl.FLOAT, false, 28, 0)
    gl.enableVertexAttribArray(ln.aCol); gl.vertexAttribPointer(ln.aCol, 4, gl.FLOAT, false, 28, 12)
  }
  function bindTris(b) {
    gl.bindBuffer(gl.ARRAY_BUFFER, b)
    gl.enableVertexAttribArray(tr.aPos); gl.vertexAttribPointer(tr.aPos, 3, gl.FLOAT, false, 28, 0)
    gl.enableVertexAttribArray(tr.aCol); gl.vertexAttribPointer(tr.aCol, 4, gl.FLOAT, false, 28, 12)
  }
  function bindPts(b) {
    gl.bindBuffer(gl.ARRAY_BUFFER, b)
    gl.enableVertexAttribArray(pt.aPos); gl.vertexAttribPointer(pt.aPos, 3, gl.FLOAT, false, 32, 0)
    gl.enableVertexAttribArray(pt.aCol); gl.vertexAttribPointer(pt.aCol, 3, gl.FLOAT, false, 32, 12)
    gl.enableVertexAttribArray(pt.aSA); gl.vertexAttribPointer(pt.aSA, 2, gl.FLOAT, false, 32, 24)
  }

  // ---------- 线框货车·细节版（墨绿线框 + 品牌绿腰线 + DIM 次级细节线）----------
  var INK = [0.22, 0.33, 0.28, 0.92]
  var GREEN = [0.09, 0.63, 0.37, 0.95]
  var DIM = [0.30, 0.44, 0.37, 0.34]
  var GRID = [0.35, 0.50, 0.42, 0.14]
  var vanArr = []
  function pushSeg(x1, y1, z1, x2, y2, z2, c) {
    vanArr.push(x1, y1, z1, c[0], c[1], c[2], c[3])
    vanArr.push(x2, y2, z2, c[0], c[1], c[2], c[3])
  }
  function boxEdges(x0, y0, z0, x1, y1, z1, c) {
    var P8 = [
      [x0, y0, z0], [x1, y0, z0], [x1, y0, z1], [x0, y0, z1],
      [x0, y1, z0], [x1, y1, z0], [x1, y1, z1], [x0, y1, z1]
    ]
    var E = [[0, 1], [1, 2], [2, 3], [3, 0], [4, 5], [5, 6], [6, 7], [7, 4], [0, 4], [1, 5], [2, 6], [3, 7]]
    E.forEach(function (e) { pushSeg(P8[e[0]][0], P8[e[0]][1], P8[e[0]][2], P8[e[1]][0], P8[e[1]][1], P8[e[1]][2], c) })
  }
  // 货箱 + 驾驶室
  boxEdges(-2.3, 0.55, -1.05, 1.35, 2.15, 1.05, INK)
  boxEdges(1.35, 0.55, -0.95, 2.65, 1.75, 0.95, INK)
  // 挡风窗（驾驶室前脸内框）
  pushSeg(2.66, 1.0, -0.55, 2.66, 1.0, 0.55, GREEN)
  pushSeg(2.66, 1.0, 0.55, 2.66, 1.5, 0.55, GREEN)
  pushSeg(2.66, 1.5, 0.55, 2.66, 1.5, -0.55, GREEN)
  pushSeg(2.66, 1.5, -0.55, 2.66, 1.0, -0.55, GREEN)
  // 品牌绿带（货箱四面腰线）
  pushSeg(-2.31, 1.32, -1.06, 1.36, 1.32, -1.06, GREEN)
  pushSeg(-2.31, 1.32, 1.06, 1.36, 1.32, 1.06, GREEN)
  pushSeg(-2.31, 1.32, -1.06, -2.31, 1.32, 1.06, GREEN)
  pushSeg(1.36, 1.32, -1.06, 1.36, 1.32, 1.06, GREEN)
  // 车顶天线
  pushSeg(-1.7, 2.15, 0, -1.7, 2.75, 0, INK)
  // —— 细节层（DIM 次级线：肋线/底盘/胎面，压低亮度保持轮廓主导）——
  // 货箱侧壁 + 顶面分格肋线
  var ribs = [-1.55, -0.8, -0.05, 0.7]
  ribs.forEach(function (rx) {
    pushSeg(rx, 0.55, -1.06, rx, 2.15, -1.06, DIM)
    pushSeg(rx, 0.55, 1.06, rx, 2.15, 1.06, DIM)
    pushSeg(rx, 2.16, -1.05, rx, 2.16, 1.05, DIM)
  })
  // 尾部双开门：门框 + 中缝 + 锁杆
  pushSeg(-2.31, 0.75, -0.85, -2.31, 2.05, -0.85, DIM)
  pushSeg(-2.31, 0.75, 0.85, -2.31, 2.05, 0.85, DIM)
  pushSeg(-2.31, 0.75, -0.85, -2.31, 0.75, 0.85, DIM)
  pushSeg(-2.31, 2.05, -0.85, -2.31, 2.05, 0.85, DIM)
  pushSeg(-2.31, 0.75, 0, -2.31, 2.05, 0, INK)
  pushSeg(-2.31, 1.1, -0.5, -2.31, 1.1, -0.2, DIM)
  pushSeg(-2.31, 1.6, -0.5, -2.31, 1.6, -0.2, DIM)
  pushSeg(-2.31, 1.1, 0.2, -2.31, 1.1, 0.5, DIM)
  pushSeg(-2.31, 1.6, 0.2, -2.31, 1.6, 0.5, DIM)
  // 驾驶室侧窗（两侧）
  ;[1, -1].forEach(function (sd) {
    pushSeg(1.55, 1.05, sd * 0.96, 2.25, 1.05, sd * 0.96, INK)
    pushSeg(1.55, 1.45, sd * 0.96, 2.25, 1.45, sd * 0.96, INK)
    pushSeg(1.55, 1.05, sd * 0.96, 1.55, 1.45, sd * 0.96, INK)
    pushSeg(2.25, 1.05, sd * 0.96, 2.25, 1.45, sd * 0.96, INK)
  })
  // 前脸：格栅横条 + 保险杠
  pushSeg(2.66, 0.72, -0.5, 2.66, 0.72, 0.5, DIM)
  pushSeg(2.66, 0.82, -0.5, 2.66, 0.82, 0.5, DIM)
  pushSeg(2.66, 0.92, -0.5, 2.66, 0.92, 0.5, DIM)
  pushSeg(2.72, 0.42, -0.98, 2.72, 0.42, 0.98, INK)
  pushSeg(2.72, 0.42, -0.98, 2.72, 0.55, -0.98, INK)
  pushSeg(2.72, 0.42, 0.98, 2.72, 0.55, 0.98, INK)
  // 底盘车架：纵梁 + 横梁
  pushSeg(-2.3, 0.42, -0.55, 2.55, 0.42, -0.55, DIM)
  pushSeg(-2.3, 0.42, 0.55, 2.55, 0.42, 0.55, DIM)
  ;[-1.55, -0.05, 1.0].forEach(function (cx) {
    pushSeg(cx, 0.42, -0.55, cx, 0.42, 0.55, DIM)
  })
  // 后视镜：支架 + 镜框（两侧）
  ;[1, -1].forEach(function (sd) {
    var mz = sd * 1.3
    pushSeg(2.55, 1.62, sd * 0.96, 2.45, 1.72, mz, INK)
    pushSeg(2.4, 1.64, mz, 2.5, 1.64, mz, INK)
    pushSeg(2.4, 1.8, mz, 2.5, 1.8, mz, INK)
    pushSeg(2.4, 1.64, mz, 2.4, 1.8, mz, INK)
    pushSeg(2.5, 1.64, mz, 2.5, 1.8, mz, INK)
  })
  // 挡泥板（轮上方半圆弧）
  function fender(cx, cz) {
    var prev = null
    for (var k = 0; k <= 8; k++) {
      var a = Math.PI * (0.08 + 0.84 * k / 8)
      var p = [cx + Math.cos(a) * 0.66, 0.52 + Math.sin(a) * 0.62, cz]
      if (prev) pushSeg(prev[0], prev[1], prev[2], p[0], p[1], p[2], DIM)
      prev = p
    }
  }
  fender(-1.55, -1.06); fender(-1.55, 1.06); fender(1.95, -1.06); fender(1.95, 1.06)
  // 车轮：外胎 + 内轮辋 + 五辐（品牌绿）+ 胎面齿
  function wheel(cx, cz) {
    var prev = null, i
    for (i = 0; i <= 18; i++) {
      var a = i / 18 * Math.PI * 2
      var p = [cx + Math.cos(a) * 0.5, 0.5 + Math.sin(a) * 0.5, cz]
      if (prev) pushSeg(prev[0], prev[1], prev[2], p[0], p[1], p[2], INK)
      prev = p
    }
    prev = null
    for (i = 0; i <= 12; i++) {
      var b = i / 12 * Math.PI * 2
      var q = [cx + Math.cos(b) * 0.27, 0.5 + Math.sin(b) * 0.27, cz]
      if (prev) pushSeg(prev[0], prev[1], prev[2], q[0], q[1], q[2], DIM)
      prev = q
    }
    for (i = 0; i < 5; i++) {
      var s = i / 5 * Math.PI * 2 + 0.3
      pushSeg(cx + Math.cos(s) * 0.05, 0.5 + Math.sin(s) * 0.05, cz, cx + Math.cos(s) * 0.27, 0.5 + Math.sin(s) * 0.27, cz, GREEN)
    }
    for (i = 0; i < 12; i++) {
      var t = i / 12 * Math.PI * 2
      pushSeg(cx + Math.cos(t) * 0.5, 0.5 + Math.sin(t) * 0.5, cz, cx + Math.cos(t) * 0.58, 0.5 + Math.sin(t) * 0.58, cz, DIM)
    }
  }
  wheel(-1.55, -1.06); wheel(-1.55, 1.06); wheel(1.95, -1.06); wheel(1.95, 1.06)
  var vanBuf = buf(new Float32Array(vanArr), false)
  var vanCount = vanArr.length / 7

  // 地面网格
  var gridArr = []
  for (var g = -4; g <= 4; g += 0.5) {
    var c = (g === 0) ? [0.25, 0.52, 0.40, 0.26] : GRID
    gridArr.push(g, 0, -4.5, c[0], c[1], c[2], c[3], g, 0, 4.5, c[0], c[1], c[2], c[3])
    gridArr.push(-4.5, 0, g, c[0], c[1], c[2], c[3], 4.5, 0, g, c[0], c[1], c[2], c[3])
  }
  var gridBuf = buf(new Float32Array(gridArr), false)
  var gridCount = gridArr.length / 7

  // —— 舞台层：量程环 + 刻度 + 四角目标框括弧 ——
  var stageArr = []
  function pushStage(x1, y1, z1, x2, y2, z2, c) {
    stageArr.push(x1, y1, z1, c[0], c[1], c[2], c[3])
    stageArr.push(x2, y2, z2, c[0], c[1], c[2], c[3])
  }
  var RING = [0.25, 0.48, 0.38, 0.26]
  var TICK = [0.25, 0.48, 0.38, 0.38]
  var BKT = [0.13, 0.52, 0.32, 0.33]
  ;[5.2, 6.0].forEach(function (rr) {
    for (var k = 0; k < 48; k++) {
      var a1 = k / 48 * Math.PI * 2, a2 = (k + 1) / 48 * Math.PI * 2
      pushStage(Math.cos(a1) * rr, 0.01, Math.sin(a1) * rr, Math.cos(a2) * rr, 0.01, Math.sin(a2) * rr, RING)
    }
  })
  for (var k2 = 0; k2 < 24; k2++) {
    var at = k2 / 24 * Math.PI * 2
    var cT = (k2 % 6 === 0) ? TICK : RING
    pushStage(Math.cos(at) * 5.85, 0.01, Math.sin(at) * 5.85, Math.cos(at) * 6.15, 0.01, Math.sin(at) * 6.15, cT)
  }
  ;[[-4.5, -4.5], [4.5, -4.5], [4.5, 4.5], [-4.5, 4.5]].forEach(function (cn) {
    var sx = cn[0] > 0 ? -1 : 1, sz = cn[1] > 0 ? -1 : 1
    pushStage(cn[0], 0.01, cn[1], cn[0] + sx * 0.9, 0.01, cn[1], BKT)
    pushStage(cn[0], 0.01, cn[1], cn[0], 0.01, cn[1] + sz * 0.9, BKT)
  })
  var stageBuf = buf(new Float32Array(stageArr), false)
  var stageCount = stageArr.length / 7

  // 远处两台静默待检车（复用整车线框，缩至 0.32，各带孪生镜像）
  var MINIS = [
    { x: -7.2, z: -5.6, a: 0.7 },
    { x: 6.9, z: -6.4, a: -0.5 }
  ]

  var scanBuf = gl.createBuffer()
  var dotBuf = gl.createBuffer()
  var reduced = window.matchMedia('(prefers-reduced-motion: reduce)').matches

  // ---------- 相机与交互 ----------
  var rotY = 0.55, pitch = 0.2, dist = 11, vrot = 0
  var dragging = false, lx = 0, ly = 0
  var listeners = []
  function listen(target, ev, fn, opt) {
    target.addEventListener(ev, fn, opt)
    listeners.push(function () { target.removeEventListener(ev, fn, opt) })
  }
  listen(cv, 'pointerdown', function (e) {
    dragging = true; lx = e.clientX; ly = e.clientY
    cv.classList.add('dragging')
    if (cv.setPointerCapture) cv.setPointerCapture(e.pointerId)
  })
  listen(window, 'pointermove', function (e) {
    if (!dragging) return
    var dx = e.clientX - lx, dy = e.clientY - ly
    lx = e.clientX; ly = e.clientY
    rotY += dx * 0.006; vrot = dx * 0.006
    pitch = Math.max(-0.15, Math.min(0.85, pitch + dy * 0.005))
    if (reduced) render(0, 0)
  })
  listen(window, 'pointerup', function () { dragging = false; cv.classList.remove('dragging') })
  listen(cv, 'wheel', function (e) {
    e.preventDefault()
    dist = Math.max(4.6, Math.min(11, dist * (1 + e.deltaY * 0.0012)))
    if (reduced) render(0, 0)
  }, { passive: false })

  function resize() {
    var DPR = Math.min(window.devicePixelRatio || 1, 2)
    var w = cv.clientWidth, h = cv.clientHeight
    if (cv.width !== w * DPR || cv.height !== h * DPR) { cv.width = w * DPR; cv.height = h * DPR }
    gl.viewport(0, 0, cv.width, cv.height)
  }

  function render(time, dt) {
    resize()
    var asp = cv.width / Math.max(1, cv.height)
    var wide = window.innerWidth > 1020
    var P = mPersp(0.75, asp, 0.1, 100)
    var V = mMul(mTrans(wide ? -1.35 : 0, -0.35, -dist), mRotX(pitch))
    var VM = mMul(P, V)

    gl.clearColor(0, 0, 0, 0)
    gl.clear(gl.COLOR_BUFFER_BIT)
    gl.enable(gl.BLEND)
    gl.blendFunc(gl.SRC_ALPHA, gl.ONE_MINUS_SRC_ALPHA)
    gl.disable(gl.DEPTH_TEST)

    // 地面网格
    gl.useProgram(PL)
    gl.uniformMatrix4fv(lnU.uMVP, false, new Float32Array(VM))
    gl.uniform1f(lnU.uAlpha, 1)
    bindLns(gridBuf)
    gl.drawArrays(gl.LINES, 0, gridCount)

    // 舞台层：量程环 + 刻度 + 角标
    bindLns(stageBuf)
    gl.drawArrays(gl.LINES, 0, stageCount)

    // 远处静默待检车（先镜像后车身，低透明度不抢主体）
    MINIS.forEach(function (mn) {
      var MM = mMul(mTrans(mn.x, 0, mn.z), mMul(mRotY(mn.a), mScale(0.32, 0.32, 0.32)))
      var MG = mMul(mTrans(mn.x, 0, mn.z), mMul(mRotY(mn.a), mScale(0.32, -0.16, 0.32)))
      gl.uniformMatrix4fv(lnU.uMVP, false, new Float32Array(mMul(P, mMul(V, MG))))
      gl.uniform1f(lnU.uAlpha, 0.32)
      bindLns(vanBuf)
      gl.drawArrays(gl.LINES, 0, vanCount)
      gl.uniformMatrix4fv(lnU.uMVP, false, new Float32Array(mMul(P, mMul(V, MM))))
      gl.uniform1f(lnU.uAlpha, 0.45)
      bindLns(vanBuf)
      gl.drawArrays(gl.LINES, 0, vanCount)
    })

    // 孪生镜像：地面下 0.5 倒置，旋转带"同步延迟"
    var ghostRot = rotY * 0.88 - 0.4
    var MVG = mMul(V, mMul(mRotY(ghostRot), mScale(1, -0.5, 1)))
    gl.uniformMatrix4fv(lnU.uMVP, false, new Float32Array(mMul(P, MVG)))
    gl.uniform1f(lnU.uAlpha, 0.34)
    bindLns(vanBuf)
    gl.drawArrays(gl.LINES, 0, vanCount)

    // 实体线框车
    var M = mRotY(rotY)
    var MVP = mMul(P, mMul(V, M))
    gl.uniformMatrix4fv(lnU.uMVP, false, new Float32Array(MVP))
    gl.uniform1f(lnU.uAlpha, 1)
    bindLns(vanBuf)
    gl.drawArrays(gl.LINES, 0, vanCount)

    // 扫描平面（上下往返，半透明玻璃片：填充可见但透出车身线，边框弱化）
    {
      var ph = reduced ? 0.3 : (time * 0.00035) % 1
      var sy = 0.55 + (1 - Math.abs(ph - 0.5) * 2) * 1.7
      var x0 = -2.7, x1 = 3.0, z0 = -1.35, z1 = 1.35
      var qA = 0.32, eA = 0.42
      var quad = [
        x0, sy, z0, 0.09, 0.7, 0.42, qA, x1, sy, z0, 0.09, 0.7, 0.42, qA, x1, sy, z1, 0.09, 0.7, 0.42, qA,
        x0, sy, z0, 0.09, 0.7, 0.42, qA, x1, sy, z1, 0.09, 0.7, 0.42, qA, x0, sy, z1, 0.09, 0.7, 0.42, qA
      ]
      gl.useProgram(PT)
      gl.uniformMatrix4fv(trU.uMVP, false, new Float32Array(MVP))
      gl.bindBuffer(gl.ARRAY_BUFFER, scanBuf)
      gl.bufferData(gl.ARRAY_BUFFER, new Float32Array(quad), gl.DYNAMIC_DRAW)
      bindTris(scanBuf)
      gl.drawArrays(gl.TRIANGLES, 0, 6)
      // 扫描面亮边
      gl.useProgram(PL)
      var edge = [
        x0, sy, z0, 0.2, 0.9, 0.55, eA, x1, sy, z0, 0.2, 0.9, 0.55, eA,
        x1, sy, z0, 0.2, 0.9, 0.55, eA, x1, sy, z1, 0.2, 0.9, 0.55, eA,
        x1, sy, z1, 0.2, 0.9, 0.55, eA, x0, sy, z1, 0.2, 0.9, 0.55, eA,
        x0, sy, z1, 0.2, 0.9, 0.55, eA, x0, sy, z0, 0.2, 0.9, 0.55, eA
      ]
      gl.uniformMatrix4fv(lnU.uMVP, false, new Float32Array(MVP))
      gl.uniform1f(lnU.uAlpha, 1)
      gl.bindBuffer(gl.ARRAY_BUFFER, scanBuf)
      gl.bufferData(gl.ARRAY_BUFFER, new Float32Array(edge), gl.DYNAMIC_DRAW)
      bindLns(scanBuf)
      gl.drawArrays(gl.LINES, 0, 8)
    }

    // 动态点：天线信标 + 车头灯
    var pulse = 0.55 + 0.45 * Math.sin(time * 0.004)
    var mr = mApplyRot(M, [-1.7, 2.78, 0])
    var h1 = mApplyRot(M, [2.68, 0.8, -0.5]), h2 = mApplyRot(M, [2.68, 0.8, 0.5])
    var dots = [
      mr[0], mr[1], mr[2], 0.95, 0.55, 0.2, 4.2, pulse,
      h1[0], h1[1], h1[2], 0.2, 0.85, 0.5, 3.4, 0.9,
      h2[0], h2[1], h2[2], 0.2, 0.85, 0.5, 3.4, 0.9
    ]
    gl.useProgram(PP)
    gl.uniformMatrix4fv(ptU.uMVP, false, new Float32Array(VM))
    gl.uniform1f(ptU.uScale, (cv.height / 2) / Math.tan(0.375))
    gl.bindBuffer(gl.ARRAY_BUFFER, dotBuf)
    gl.bufferData(gl.ARRAY_BUFFER, new Float32Array(dots), gl.DYNAMIC_DRAW)
    bindPts(dotBuf)
    gl.drawArrays(gl.POINTS, 0, dots.length / 8)
  }

  function mApplyRot(m, p) {
    // 仅旋转矩阵（无平移）作用于点
    return [
      m[0] * p[0] + m[4] * p[1] + m[8] * p[2],
      m[1] * p[0] + m[5] * p[1] + m[9] * p[2],
      m[2] * p[0] + m[6] * p[1] + m[10] * p[2]
    ]
  }

  var last = 0, running = false, rafId = 0
  function loop(t) {
    if (!running) return
    var dt = Math.min((t - last) / 1000, 0.05); last = t
    if (!dragging) { rotY += vrot; vrot *= 0.93; rotY += 0.25 * dt }
    render(t, dt)
    rafId = requestAnimationFrame(loop)
  }
  listen(window, 'resize', function () { if (reduced) render(0, 0) })
  var onVis = function () {
    running = !document.hidden && !reduced
    if (running) { last = performance.now(); rafId = requestAnimationFrame(loop) }
  }
  document.addEventListener('visibilitychange', onVis)
  listeners.push(function () { document.removeEventListener('visibilitychange', onVis) })

  // canvas 尺寸跟随（fixed 全屏，主要吃窗口 resize；RO 兜底 SPA 路由切换等场景）
  var ro = new ResizeObserver(function () { if (reduced) render(0, 0) })
  ro.observe(cv)

  running = !reduced
  if (reduced) render(0, 0)
  else { last = performance.now(); rafId = requestAnimationFrame(loop) }

  dispose = function () {
    running = false
    if (rafId) cancelAnimationFrame(rafId)
    listeners.forEach(function (off) { off() })
    listeners = []
    if (ro) ro.disconnect()
    ;[vanBuf, gridBuf, stageBuf, scanBuf, dotBuf].forEach(function (b) { gl.deleteBuffer(b) })
    ;[PL, PT, PP].forEach(function (p) { gl.deleteProgram(p) })
    var ext = gl.getExtension('WEBGL_lose_context')
    if (ext) ext.loseContext()
  }
})

onBeforeUnmount(() => { if (dispose) dispose() })
</script>

<style scoped>
.twin-stage {
  position: fixed; inset: 0; z-index: 0; width: 100%; height: 100%;
  cursor: grab; touch-action: none;
}
.twin-stage.dragging { cursor: grabbing; }
</style>

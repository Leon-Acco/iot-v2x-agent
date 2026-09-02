
// 登录页交互增强:鼠标涟漪 / 聚焦场景响应 / 沉浸视差 / 按钮逐字模糊
(function () {
  'use strict';
  var page = document.getElementById('page-login');
  if (!page) return;
  var reducedMotion = window.matchMedia && window.matchMedia('(prefers-reduced-motion: reduce)').matches;

  /* ---------- 1. 鼠标涟漪:二维波动方程高度场模拟真实水面 ----------
     原理:低分辨率网格上跑经典水波方程(next=四邻均值-prev,乘衰减),
     鼠标划过=落雨滴,波纹自然扩散/反射/干涉;渲染时按高度梯度算明暗
     (128 灰为中性,overlay 混合下波峰提亮、波谷压暗),借 mesh 渐变呈现折射感 */
  var rippleCanvas = document.getElementById('lp-ripple');
  if (rippleCanvas && !reducedMotion) {
    var rctx = rippleCanvas.getContext('2d');
    var CELL = 3.5;                         // 模拟格对应 CSS px(低分辨率模拟+平滑放大,快且柔)
    var DAMPING = 0.98;                     // 能量衰减,越接近 1 波纹拖得越久
    var LIGHT_K = 1.3;                      // 高度梯度 -> 明暗 alpha 系数(柔和水面,过大会变黑线)
    var DROP_DIST = 16;                     // 指针每移动该距离落一颗雨滴
    var IDLE_STOP = 600;                    // 无交互多少帧后停帧省电(0.986^600 波纹已不可见)
    var sim = null;                         // {w,h,cur,prev,off,offCtx,img}
    var rafId = null, idleFrames = 0;
    var lastX = -1e9, lastY = -1e9;

    function syncSize() {
      var w = rippleCanvas.clientWidth, h = rippleCanvas.clientHeight;
      if (!w || !h) return false;           // 切页后 canvas 隐藏尺寸为 0,跳过防 1×1 钳制
      // 水面求柔不求锐,像素比压到 1.25 省填充率
      var dpr = Math.min(window.devicePixelRatio || 1, 1.25);
      var W = Math.round(w * dpr), H = Math.round(h * dpr);
      if (rippleCanvas.width !== W || rippleCanvas.height !== H) {
        rippleCanvas.width = W; rippleCanvas.height = H;
      }
      var sw = Math.max(48, Math.round(w / CELL)), sh = Math.max(48, Math.round(h / CELL));
      if (!sim || sim.w !== sw || sim.h !== sh) {
        var off = document.createElement('canvas');
        off.width = sw; off.height = sh;
        sim = {
          w: sw, h: sh,
          cur: new Float32Array(sw * sh),
          prev: new Float32Array(sw * sh),
          off: off,
          offCtx: off.getContext('2d'),
        };
        sim.img = sim.offCtx.createImageData(sw, sh);
      }
      return true;
    }

    // 在 canvas CSS 像素坐标 (x,y) 处落一颗雨滴(radius 以模拟格计)
    function drop(x, y, radius, strength) {
      if (!sim) return;
      var cx = Math.round(x / CELL), cy = Math.round(y / CELL);
      for (var dy = -radius; dy <= radius; dy++) {
        for (var dx = -radius; dx <= radius; dx++) {
          var d = Math.sqrt(dx * dx + dy * dy);
          if (d > radius) continue;
          var px = cx + dx, py = cy + dy;
          if (px < 1 || py < 1 || px >= sim.w - 1 || py >= sim.h - 1) continue;
          sim.cur[py * sim.w + px] += strength * (1 - d / (radius + 1));
        }
      }
      idleFrames = 0;
    }

    function step() {
      // 波动方程:next = 四邻均值 - prev,乘衰减;算完交换缓冲,cur 恒为最新高度场
      var w = sim.w, h = sim.h, cur = sim.cur, prev = sim.prev;
      for (var y = 1; y < h - 1; y++) {
        var row = y * w;
        for (var x = 1; x < w - 1; x++) {
          var i = row + x;
          prev[i] = ((cur[i - 1] + cur[i + 1] + cur[i - w] + cur[i + w]) * 0.5 - prev[i]) * DAMPING;
        }
      }
      sim.prev = cur; sim.cur = prev;
    }

    function render() {
      // 高度场 -> 直接上色:波谷=半透明深 teal 阴影,波峰=白色高光,alpha 随坡度增强
      var w = sim.w, h = sim.h, cur = sim.cur, data = sim.img.data;
      for (var y = 1; y < h - 1; y++) {
        var row = y * w;
        for (var x = 1; x < w - 1; x++) {
          var i = row + x;
          var g = ((cur[i + 1] - cur[i - 1]) + (cur[i + w] - cur[i - w]) * 0.6) * LIGHT_K;
          var j = i * 4;
          if (g < 0) {
            var a = -g; if (a > 150) a = 150;
            data[j] = 11; data[j + 1] = 74; data[j + 2] = 66; data[j + 3] = a;
          } else {
            var a2 = g; if (a2 > 170) a2 = 170;
            data[j] = 255; data[j + 1] = 255; data[j + 2] = 255; data[j + 3] = a2;
          }
        }
      }
      sim.offCtx.putImageData(sim.img, 0, 0);
      rctx.clearRect(0, 0, rippleCanvas.width, rippleCanvas.height);
      rctx.imageSmoothingQuality = 'high';    // 低分辨率平滑放大,波纹更柔
      rctx.drawImage(sim.off, 0, 0, rippleCanvas.width, rippleCanvas.height);
    }

    function frame() {
      rafId = null;
      if (!page.classList.contains('active')) return; // 切页停帧,pointermove/布局变化会唤醒
      if (!syncSize()) return;
      if (++idleFrames > IDLE_STOP) {         // 水面已平静,停帧省电(透明画布 overlay 无效果)
        rctx.clearRect(0, 0, rippleCanvas.width, rippleCanvas.height);
        return;
      }
      step();
      render();
      rafId = requestAnimationFrame(frame);
    }
    function wake() { if (!rafId && syncSize()) rafId = requestAnimationFrame(frame); }

    page.addEventListener('pointermove', function (e) {
      if (!page.classList.contains('active')) return;
      if (Math.abs(e.clientX - lastX) + Math.abs(e.clientY - lastY) < DROP_DIST) return;
      var rect = rippleCanvas.getBoundingClientRect();
      if (!rect.width) return;
      lastX = e.clientX; lastY = e.clientY;
      drop(e.clientX - rect.left, e.clientY - rect.top, 2, 85 + Math.random() * 45);
      wake();
    });
    // 点按落一颗大雨滴,溅起同心波
    page.addEventListener('pointerdown', function (e) {
      if (!page.classList.contains('active')) return;
      var rect = rippleCanvas.getBoundingClientRect();
      if (!rect.width) return;
      drop(e.clientX - rect.left, e.clientY - rect.top, 3, 420);
      wake();
    });
    // 环境雨滴:无人操作时也偶有微澜,水面是活的
    setInterval(function () {
      if (!page.classList.contains('active') || document.hidden || !sim) return;
      drop(Math.random() * sim.w * CELL, Math.random() * sim.h * CELL, 1, 60 + Math.random() * 60);
      wake();
    }, 2600);

    window.addEventListener('resize', wake);
    window.addEventListener('lp-layout-change', wake);
    wake();
  }

  /* ---------- 2. 输入框聚焦的页面级响应 + 提交时的"吸气"脉冲 ---------- */
  var form = page.querySelector('.lp-form');
  var fields = page.querySelectorAll('.lp-field input');
  for (var fi = 0; fi < fields.length; fi++) {
    fields[fi].addEventListener('focus', function () { page.classList.add('lp-focus'); });
    fields[fi].addEventListener('blur', function () { page.classList.remove('lp-focus'); });
  }
  if (form && !reducedMotion) {
    form.addEventListener('submit', function () {
      page.classList.remove('lp-inhale');
      void page.offsetWidth;                // 强制回流以重启动画
      page.classList.add('lp-inhale');
      setTimeout(function () { page.classList.remove('lp-inhale'); }, 260);
    });
  }

  /* ---------- 3. 沉浸布局视差分层(球 8px / 文案 4px / 表单反向 2px,rAF 节流 + lerp) ---------- */
  if (!reducedMotion) {
    var tx = 0, ty = 0, cx = 0, cy = 0, parallaxRaf = null;
    function parallaxFrame() {
      parallaxRaf = null;
      cx += (tx - cx) * 0.08;
      cy += (ty - cy) * 0.08;
      page.style.setProperty('--lp-pxm', (cx * 8).toFixed(2) + 'px');
      page.style.setProperty('--lp-pym', (cy * 8).toFixed(2) + 'px');
      page.style.setProperty('--lp-pxc', (cx * 4).toFixed(2) + 'px');
      page.style.setProperty('--lp-pyc', (cy * 4).toFixed(2) + 'px');
      page.style.setProperty('--lp-pxf', (-cx * 2).toFixed(2) + 'px');
      page.style.setProperty('--lp-pyf', (-cy * 2).toFixed(2) + 'px');
      if (Math.abs(tx - cx) > 0.001 || Math.abs(ty - cy) > 0.001) {
        parallaxRaf = requestAnimationFrame(parallaxFrame);
      }
    }
    page.addEventListener('pointermove', function (e) {
      if (!page.classList.contains('lp-immersive') || window.innerWidth <= 767) return;
      tx = (e.clientX / window.innerWidth - 0.5) * 2;   // 归一化 -1..1
      ty = (e.clientY / window.innerHeight - 0.5) * 2;
      if (!parallaxRaf) parallaxRaf = requestAnimationFrame(parallaxFrame);
    });
  }

  /* ---------- 4. 按钮逐字模糊:label 拆 char span;app.js 改文案统一走钩子重拆 ---------- */
  var loginBtn = document.getElementById('login-btn');
  function splitChars(btn) {
    var label = btn && btn.querySelector('.lp-btn-label');
    if (!label) return;
    var text = label.textContent;
    label.innerHTML = '';
    for (var i = 0; i < text.length; i++) {
      var s = document.createElement('span');
      s.className = 'lp-ch';
      s.textContent = text[i];
      s.style.transitionDelay = (i * 45) + 'ms';      // 逐字错峰
      label.appendChild(s);
    }
  }
  if (loginBtn && !reducedMotion) splitChars(loginBtn);
  // app.js 三处 label 文案修改经此钩子(直写 textContent 会拍平 char span,改完须重拆)
  window.__lpSetBtnLabel = function (btn, text) {
    var label = btn && btn.querySelector('.lp-btn-label');
    if (!label) return;
    label.textContent = text;
    if (!reducedMotion) splitChars(btn);
  };
})();


// RenderingPipeline (nn L52009-52130) + Copy (v1 L51374-51416) + Bloom (Bte
// L50779-50882, shaders Tte/Ite L50733-50776 verbatim) + composer fx
// concatenation (Mte L50693-50726 pattern): Bloom-combine → Halo (Ote
// L51489-51531) → NeutralToneMapping (Ute L51533-51561) → LinearTosRGB →
// Dither (Nte L51427-51443). SMAA (jte) 打包但 enabled=!1（怪癖 Q7），不移植。
// LinearTosRGB body pending verbatim-extract (formula-standard meanwhile).
import {
  Color,
  Frustum,
  HalfFloatType,
  LinearFilter,
  Matrix4,
  Mesh,
  MirroredRepeatWrapping,
  NoToneMapping,
  PerspectiveCamera,
  PlaneGeometry,
  RGBAFormat,
  ShaderMaterial,
  Sphere,
  Vector2,
  WebGLRenderer,
  WebGLRenderTarget,
  LinearSRGBColorSpace,
} from "three";
import {
  app,
  appSettings,
  getProvider,
  setProvider,
  injectUniforms,
  Viewport,
  events,
  Tick,
  type TickFrame,
} from "./core";
import { ShaderRegistry } from "./shaderRegistry";
import { Performance } from "./performance";
import { isGlassDispersion } from "./scene/glass/materials";

// Hy — fullscreen vertex, camera-independent (L50684-50689).
const FULLSCREEN_VERT = /* glsl */ `
varying vec2 vUv;
void main() {
  vUv = uv;
  gl_Position = vec4(2.0 * uv - 1.0, 0.0, 1.0);
}
`;

// Tte — separable gaussian (verbatim L50727-50758).
const BLUR_FRAG = `varying vec2 vUv;
uniform sampler2D t;
uniform vec2 invSize;
uniform vec2 direction;

float gaussianPdf(float x, float sigma) {
  return .39894 * exp(-.5 * x * x / (sigma * sigma)) / sigma;
}

void main() {
  float fSigma = float(SIGMA);

  float weightSum = gaussianPdf(0., fSigma);
  vec3 diffuseSum = texture2D(t, vUv).rgb * weightSum;

  #pragma unroll_loop_start
  for (int i = 1; i < KERNEL_RADIUS; i++) {
    float x = float(i);
    float w = gaussianPdf(x, fSigma);
    vec2 uvOffset = direction * invSize * x;
    vec3 sample1 = texture2D(t, vUv + uvOffset).rgb;
    vec3 sample2 = texture2D(t, vUv - uvOffset).rgb;
    diffuseSum += (sample1 + sample2) * w;
    weightSum += w + w;
  }
  #pragma unroll_loop_end

  gl_FragColor = vec4(diffuseSum / weightSum, 1.);
}`;

// Ite — luminance threshold filter (verbatim L50759-50776).
const FILTER_FRAG = `varying vec2 vUv;

uniform sampler2D t;
uniform float threshold;
uniform float width;
uniform vec2 texelSize;

void main() {
  vec2 uv = vUv;
  vec3 color = texture2D(t, uv).rgb;


  float l = dot(color, vec3(0.299, 0.587, 0.114));
  l = smoothstep(threshold - width, threshold + width, l);
  color = max(color * l, 0.);

  gl_FragColor = vec4(color, 1.);
}`;

const DIR_H = new Vector2(1, 0);
const DIR_V = new Vector2(0, 1);

interface Fx {
  out: {
    uniforms: Record<string, { value: any }>;
    declarations: string;
    inject: string;
  };
  render?: (renderer: WebGLRenderer, quad: Mesh, camera: PerspectiveCamera) => void;
  onResize?: (v: { x: number; y: number }) => void;
}

// v1 —— Copy pass（L51374-51416）：把 inBinder 纹理抄进自有 RT 并挂到
// outBinder（默认 Copy.map）。材质经 ri 注入 `map@inBinder` + `@sweet alpha`
// （gte 变换器，devMode 下为 1.）；update/clear 走 app.pipeline.renderer +
// app.camera（X 替身，由 Engine 装配填充）。
export class Copy {
  static mapProvider = "Copy.map";
  scale: number;
  width: number;
  height: number;
  rt: WebGLRenderTarget;
  mesh: Mesh;
  constructor({
    inBinder,
    outBinder,
    scale = 1,
  }: {
    inBinder: string;
    outBinder: string;
    scale?: number;
  }) {
    (this.scale = scale),
      (this.width = app.viewport.x * this.scale),
      (this.height = app.viewport.y * this.scale),
      (this.rt = new WebGLRenderTarget(this.width, this.height, {
        type: HalfFloatType,
        format: RGBAFormat,
        generateMipmaps: false,
      })),
      (this.rt.texture.wrapS = this.rt.texture.wrapT = MirroredRepeatWrapping),
      setProvider(outBinder, this.rt.texture);
    const material = new ShaderMaterial(
      injectUniforms({
        vertexShader: `
        varying vec2 vUv;
        void main() {
          vUv = uv;
          gl_Position = vec4(uv * 2. - 1., 0., 1.);
        }
      `,
        fragmentShader: `
        varying vec2 vUv;
        uniform sampler2D map@${inBinder};
        void main() {
          vec3 color = texture2D(map, vUv).rgb;
          gl_FragColor = vec4(color, @sweet alpha);
        }
      `,
        depthTest: false,
        depthWrite: false,
      } as any),
    );
    ShaderRegistry.add(material as any),
      (this.mesh = new Mesh(new PlaneGeometry(1, 1, 1, 1), material)),
      (this.mesh.frustumCulled = false),
      appSettings.devMode && (material.name = "Copy"),
      events.on(Viewport.resizeEvent, this.onResize);
  }
  update = () => {
    const e = app.pipeline.renderer as WebGLRenderer;
    e.setRenderTarget(this.rt), e.render(this.mesh, app.camera);
  };
  clear = () => {
    const e = app.pipeline.renderer as WebGLRenderer;
    e.setRenderTarget(this.rt), e.clear(true, true, true);
  };
  onResize = ({ x, y }: { x: number; y: number }) => {
    (this.width = x * this.scale),
      (this.height = y * this.scale),
      this.rt.setSize(this.width, this.height);
  };
}

// Bte — 5-mip bloom pyramid.
class Bloom implements Fx {
  renderTargetsHorizontal: WebGLRenderTarget[] = [];
  renderTargetsVertical: WebGLRenderTarget[] = [];
  blurMaterials: ShaderMaterial[] = [];
  nMips = 5;
  filterRenderTarget: WebGLRenderTarget;
  filterMaterial: ShaderMaterial;
  constructor(viewport: Viewport) {
    setProvider("post.bloom.filter.threshold", 1);
    setProvider("post.bloom.filter.width", 0.25);
    setProvider("post.bloom.power", 1 / 6);
    setProvider("post.bloom.radius", 2 / 3);
    const kernels = [3, 5, 7, 9, 11];
    let w = viewport.x;
    let h = viewport.y;
    for (let i = 0; i < this.nMips; i++) {
      this.renderTargetsHorizontal.push(new WebGLRenderTarget(w, h, RenderingPipeline.rtParameters));
      this.renderTargetsVertical.push(new WebGLRenderTarget(w, h, RenderingPipeline.rtParameters));
      const k = kernels[i];
      this.blurMaterials.push(
        new ShaderMaterial({
          defines: { KERNEL_RADIUS: k, SIGMA: k },
          uniforms: {
            t: { value: null },
            invSize: { value: new Vector2(1 / w, 1 / h) },
            direction: { value: new Vector2(0.5, 0.5) },
          },
          vertexShader: FULLSCREEN_VERT,
          fragmentShader: BLUR_FRAG,
          depthTest: false,
          depthWrite: false,
        }),
      );
      w = Math.round(w * 0.5);
      h = Math.round(h * 0.5);
    }
    this.filterRenderTarget = new WebGLRenderTarget(viewport.x, viewport.y, RenderingPipeline.rtParameters);
    this.filterMaterial = new ShaderMaterial({
      uniforms: {
        t: getProvider(RenderingPipeline.screenMapProvider),
        threshold: getProvider("post.bloom.filter.threshold"),
        width: getProvider("post.bloom.filter.width"),
        texelSize: { value: viewport.iv2 },
      },
      vertexShader: FULLSCREEN_VERT,
      fragmentShader: FILTER_FRAG,
      depthTest: false,
      depthWrite: false,
    });
    setProvider("post.bloom.filter.result", this.filterRenderTarget.texture);
    for (let i = 0; i < 5; i++)
      setProvider(`post.bloom.level${i}`, this.renderTargetsVertical[i].texture);
    events.on(Viewport.resizeEvent, this.onResize);
  }
  out = {
    uniforms: {
      bloomLevel0: getProvider("post.bloom.level0", null),
      bloomLevel1: getProvider("post.bloom.level1", null),
      bloomLevel2: getProvider("post.bloom.level2", null),
      bloomLevel3: getProvider("post.bloom.level3", null),
      bloomLevel4: getProvider("post.bloom.level4", null),
      bloomRadius: getProvider("post.bloom.radius", 0),
      bloomPower: getProvider("post.bloom.power", 0),
    },
    declarations: `
uniform sampler2D bloomLevel0;
uniform sampler2D bloomLevel1;
uniform sampler2D bloomLevel2;
uniform sampler2D bloomLevel3;
uniform sampler2D bloomLevel4;
uniform float bloomRadius;
uniform float bloomPower;
    `,
    inject: `
vec3 bloom = (
  mix(1.0, 0.2, bloomRadius) * texture2D(bloomLevel0, uv).rgb +
  mix(0.8, 0.4, bloomRadius) * texture2D(bloomLevel1, uv).rgb +
  0.6 * texture2D(bloomLevel2, uv).rgb +
  mix(0.4, 0.8, bloomRadius) * texture2D(bloomLevel3, uv).rgb +
  mix(0.2, 1.0, bloomRadius) * texture2D(bloomLevel4, uv).rgb
);
color += bloomPower * bloom * bloom;
    `,
  };
  render = (renderer: WebGLRenderer, quad: Mesh, camera: PerspectiveCamera) => {
    const autoClear = renderer.autoClear;
    renderer.autoClear = false;
    quad.material = this.filterMaterial;
    renderer.setRenderTarget(this.filterRenderTarget);
    renderer.render(quad, camera);
    let input: WebGLRenderTarget = this.filterRenderTarget;
    for (let i = 0; i < this.nMips; i++) {
      const material = this.blurMaterials[i];
      quad.material = material;
      material.uniforms.t.value = input.texture;
      material.uniforms.direction.value = DIR_H;
      renderer.setRenderTarget(this.renderTargetsHorizontal[i]);
      renderer.render(quad, camera);
      material.uniforms.t.value = this.renderTargetsHorizontal[i].texture;
      material.uniforms.direction.value = DIR_V;
      renderer.setRenderTarget(this.renderTargetsVertical[i]);
      renderer.render(quad, camera);
      input = this.renderTargetsVertical[i];
    }
    renderer.autoClear = autoClear;
  };
  onResize = ({ x, y }: { x: number; y: number }) => {
    let w = x;
    let h = y;
    for (let i = 0; i < this.nMips; i++) {
      (this.blurMaterials[i].uniforms.invSize.value as Vector2).set(1 / w, 1 / h);
      this.renderTargetsHorizontal[i].setSize(w, h);
      this.renderTargetsVertical[i].setSize(w, h);
      w = Math.round(w * 0.5);
      h = Math.round(h * 0.5);
    }
    this.filterRenderTarget.setSize(x, y);
  };
}

// Ote — halo: center-mirrored bloom mips with RGB anaglyph, edge-masked.
class Halo implements Fx {
  out;
  constructor(viewport: Viewport, timelineBind: (provider: string, path: string) => void) {
    this.out = {
      uniforms: {
        aspectRatio: { value: viewport.aspectRatioV2 },
        haloPower: getProvider("post.halo", 0.5),
        haloMin: getProvider("post.halo.min", 0.5),
        haloMax: getProvider("post.halo.max", 0.75),
        haloShift: getProvider("post.halo.shift", 0.6667),
        haloAnaglyphWidth: getProvider("post.halo.anaglyph.width", 12.5),
      },
      declarations: `
uniform vec2 aspectRatio;
uniform float haloPower;
uniform float haloMin;
uniform float haloMax;
uniform float haloShift;
uniform float haloAnaglyphWidth;
    `,
      inject: `
{
  vec2 fromCenter = (vUv - 0.5) * aspectRatio;
  vec2 direction = normalize(fromCenter);
  vec2 st = 0.5 - fromCenter + direction * haloShift;
  vec2 anaglyph = direction * texelSize.y * haloAnaglyphWidth;
  vec3 halo = 0.25 * vec3(
    texture2D(bloomLevel2, st - anaglyph).r,
    texture2D(bloomLevel2, st).g,
    texture2D(bloomLevel2, st + anaglyph).b
  );
  halo += vec3(
    texture2D(bloomLevel4, st - anaglyph * 8.).r,
    texture2D(bloomLevel4, st).g,
    texture2D(bloomLevel4, st + anaglyph * 8.).b
  );
  color += halo * haloPower * smoothstep(haloMin, haloMax, length(fromCenter));
}
    `,
    };
    timelineBind("post.halo", "Post_halo.position.x");
    timelineBind("post.halo.min", "Post_haloMinMaxShift.position.x");
    timelineBind("post.halo.max", "Post_haloMinMaxShift.position.y");
    timelineBind("post.halo.shift", "Post_haloMinMaxShift.position.z");
  }
}

// Ute — Khronos PBR Neutral (verbatim).
const toneMappingFx: Fx = {
  out: {
    uniforms: { exposure: getProvider("exposure", 1) },
    declarations: `
      uniform float exposure;
      vec3 NeutralToneMapping(vec3 color) {
        const float StartCompression = 0.8 - 0.04;
        const float Desaturation = 0.15;
        color *= exposure;
        float x = min(color.r, min(color.g, color.b));
        float offset = x < 0.08 ? x - 6.25 * x * x : 0.04;
        color -= offset;
        float peak = max(color.r, max(color.g, color.b));
        if (peak < StartCompression) return color;
        float d = 1. - StartCompression;
        float newPeak = 1. - d * d / (peak + d - StartCompression);
        color *= newPeak / peak;
        float g = 1. - 1. / (Desaturation * (peak - newPeak) + 1.);
        return mix(color, vec3(newPeak), g);
      }
    `,
    inject: `
      color = max(color, vec3(0.));
      color = NeutralToneMapping(color);
    `,
  },
};

const linearToSRGBFx: Fx = {
  out: {
    uniforms: {},
    declarations: `
vec3 LinearTosRGB(vec3 c) {
  return mix(c * 12.92, 1.055 * pow(c, vec3(1.0 / 2.4)) - 0.055, step(0.0031308, c));
}
    `,
    inject: `
      color = LinearTosRGB(color);
    `,
  },
};

// Nte — dither (verbatim; note fract(c) inside the dot).
const ditherFx: Fx = {
  out: {
    uniforms: { seconds: getProvider(Tick.secondsProvider) },
    declarations: `
uniform float seconds;
float rand(vec2 c) {
  return fract(sin(dot(fract(c), vec2(12.9898, 78.233))) * 43758.5453);
}

`,
    inject: `{
      float rnd = rand(vUv + fract(seconds));
      color += rnd / 256. - 1. / 512.;
    }`,
  },
};

export class RenderingPipeline {
  static screenMapProvider = "Post.map";
  static backMapProvider = "Post.backMap";
  static addPrerenderableEvent = "RenderingPipeline.addPrerenderableEvent";
  prerenderables: Record<string, { render: (frame?: any) => void }> = {};
  renderer: WebGLRenderer;
  viewport: Viewport;
  rt: WebGLRenderTarget;
  backRT: WebGLRenderTarget;
  copy!: Copy;
  background = new Color(0xe5e9f9);
  shouldRenderLayers = true;
  // 怪癖④照抄：clearAlpha = devMode ? 1 : 0（REBUILD_PLAN §6）
  clearAlpha = appSettings.devMode ? 1 : 0;
  msaaEnabled = true;
  usePadding = true;
  normalDPR = true;
  frustum = new Frustum();
  projScreenMatrix = new Matrix4();
  cullSphere = new Sphere();
  bloom: Bloom;
  fx: Fx[];
  #composerMesh: Mesh;
  #composerMaterial: ShaderMaterial;

  static rtParameters = {
    magFilter: LinearFilter,
    minFilter: LinearFilter,
    generateMipmaps: false,
    type: HalfFloatType,
    format: RGBAFormat,
    colorSpace: LinearSRGBColorSpace,
  } as const;

  constructor(viewport: Viewport, timelineBind: (provider: string, path: string) => void) {
    this.viewport = viewport;
    this.renderer = new WebGLRenderer({
      antialias: false,
      powerPreference: "high-performance",
      premultipliedAlpha: false,
    });
    this.renderer.outputColorSpace = LinearSRGBColorSpace;
    this.renderer.toneMapping = NoToneMapping;
    this.rt = new WebGLRenderTarget(1, 1, {
      ...RenderingPipeline.rtParameters,
      samples: this.getMSAASamples(),
    });
    this.rt.texture.wrapS = this.rt.texture.wrapT = MirroredRepeatWrapping;
    setProvider(RenderingPipeline.screenMapProvider, this.rt.texture);
    this.backRT = new WebGLRenderTarget(1, 1, { ...RenderingPipeline.rtParameters });
    // 源站 backRT 同样 MirroredRepeat（nn.create L52046）
    this.backRT.texture.wrapS = this.backRT.texture.wrapT = MirroredRepeatWrapping;
    setProvider(RenderingPipeline.backMapProvider, this.backRT.texture);
    this.copy = new Copy({
      inBinder: RenderingPipeline.screenMapProvider,
      outBinder: Copy.mapProvider,
      scale: 1,
    });

    this.bloom = new Bloom(viewport);
    this.fx = [this.bloom, new Halo(viewport, timelineBind), toneMappingFx, linearToSRGBFx, ditherFx];

    // Mte — one fullscreen uber-shader concatenated from fx declarations/injects.
    const uniforms: Record<string, { value: any }> = {
      map: getProvider(RenderingPipeline.screenMapProvider),
      texelSize: { value: viewport.iv2 },
    };
    for (const fx of this.fx) Object.assign(uniforms, fx.out.uniforms);
    const fragment = `
varying vec2 vUv;
uniform sampler2D map;
uniform vec2 texelSize;
${this.fx.map((f) => f.out.declarations).join("\n")}
void main() {
  vec2 uv = vUv;
  @sweet padding-uv uv;
  vec3 color = texture2D(map, uv).rgb;
${this.fx.map((f) => f.out.inject).join("\n")}
  gl_FragColor = vec4(color, 1.0);
}
`;
    this.#composerMaterial = new ShaderMaterial({
      vertexShader: FULLSCREEN_VERT,
      fragmentShader: fragment,
      uniforms,
      depthTest: false,
      depthWrite: false,
    });
    ShaderRegistry.add(this.#composerMaterial as any);
    this.#composerMesh = new Mesh(new PlaneGeometry(1, 1, 1, 1), this.#composerMaterial);
    this.#composerMesh.frustumCulled = false;

    // nn.create 尾部事件接线（L52053-52055）；Sn.resizeEvent → onResize 由
    // Engine 装配处接线（index.ts），此处不重复监听。
    events.on(Performance.msaaEvent, this.onMSAAEvent);
    events.on(Performance.normalDPREvent, this.onNormalDPREvent);
    events.on(Performance.screenPaddingEvent, this.onScreenPaddingEvent);
    events.on(RenderingPipeline.addPrerenderableEvent, this.onAddPrerenderable);
  }

  setContainer = (el: HTMLElement) => {
    el.appendChild(this.renderer.domElement);
    this.onResize(this.viewport);
  };

  // nn.onResize（L52110-52113）：不解构默认值、不 round —— RT 尺寸可为浮点。
  onResize = ({ x, y, dpr }: { x: number; y: number; dpr: number }) => {
    this.renderer.setSize(x, y);
    const r = this.normalDPR ? dpr : 1;
    this.renderer.setPixelRatio(r);
    const s = this.usePadding ? 1.25 : 1,
      o = x * r * s,
      a = y * r * s;
    this.updateRT(o, a), this.backRT.setSize(o, a);
  };

  onAddPrerenderable = (e: string, t: { render: (frame?: TickFrame) => void }) => {
    this.prerenderables[e] = t;
  };

  onMSAAEvent = (e: boolean) => {
    (this.msaaEnabled = e), this.updateRT();
  };

  onNormalDPREvent = (e: boolean) => {
    (this.normalDPR = e), this.onResize(this.viewport);
  };

  onScreenPaddingEvent = (e: boolean) => {
    (this.usePadding = e), this.onResize(this.viewport);
  };

  // nn.updateShouldRenderLayers（L52058-52059）：CasePage/ContactsPage 地图
  // 打开时跳过场景渲染。Jr.useCaseMapProvider="CasePage.useCaseMap"、
  // Ms.useMapProvider="ContactsPage.useMap"；两 fx 属 M6，provider 缺省 0。
  updateShouldRenderLayers = () => {
    this.shouldRenderLayers =
      getProvider("CasePage.useCaseMap", 0).value < 1 &&
      getProvider("ContactsPage.useMap", 0).value < 1;
  };

  // nn.getMSAASamples（L52100-52104）
  getMSAASamples = () => {
    const { x, y, dpr } = this.viewport,
      r = x * dpr,
      s = y * dpr;
    return this.msaaEnabled && dpr <= 1 && r < 2048 && s < 2048 ? 4 : 0;
  };

  // nn.updateRT（L52105-52109）：尺寸不变且 samples 不变则不动；仅尺寸不变
  // 时 dispose 后再 setSize（samples 变更需要重建底层帧缓冲）。
  updateRT = (e = this.rt.width, t = this.rt.height) => {
    const i = this.rt.width === e && this.rt.height === t,
      r = this.rt.samples,
      s = this.getMSAASamples();
    (i && r === s) ||
      ((this.rt.samples = s), i && this.rt.dispose(), this.rt.setSize(e, t));
  };

  // nn.renderLayers（L52060-52078）：layers = X.layerController.layers 裸对象
  // 数组（Mn.onTick 每帧重排，此处逐帧直读）。
  renderLayers = (camera: PerspectiveCamera, target: WebGLRenderTarget) => {
    if (!this.shouldRenderLayers) return;
    let i = false;
    const layers = app.layerController.layers;
    setProvider(RenderingPipeline.screenMapProvider, target.texture),
      this.projScreenMatrix.multiplyMatrices(camera.projectionMatrix, camera.matrixWorldInverse),
      this.frustum.setFromProjectionMatrix(this.projScreenMatrix),
      (this.renderer.autoClear = false),
      this.renderer.setClearColor(this.background, this.clearAlpha),
      this.renderer.setRenderTarget(target),
      this.renderer.clear(true, true, true),
      this.renderer.setRenderTarget(this.backRT),
      this.renderer.clear(true, true, true),
      this.copy.clear();
    for (let r = 0; r < layers.length; r++) {
      const s: any = layers[r];
      if (s.visible === false) continue;
      if (s.frustumCulled === true && s instanceof Mesh) {
        if ((s.updateMatrixWorld(), isGlassDispersion(s))) {
          if (
            (s.geometry.boundingSphere || s.geometry.computeBoundingSphere(),
            s.geometry.boundingSphere &&
              (this.cullSphere.center.copy(s.geometryWorld),
              (this.cullSphere.radius = s.geometry.boundingSphere.radius),
              !this.frustum.intersectsSphere(this.cullSphere)))
          )
            continue;
        } else if (!this.frustum.intersectsObject(s)) continue;
      }
      "needsCopy" in s && s.needsCopy && i && this.copy.update();
      const o = s.frustumCulled;
      (s.frustumCulled = false),
        s.render
          ? s.render(this, camera, target)
          : (this.renderer.setRenderTarget(target), this.renderer.render(s, camera)),
        (i = true),
        (s.frustumCulled = o);
    }
    this.renderer.autoClear = true;
  };

  // nn.render（L52080-52082）：SMAA（jte）打包但 enabled=!1（怪癖 Q7），
  // 不移植 —— `smaa.shouldRender = shouldRenderLayers` 一行随之略去。
  render = (camera: PerspectiveCamera, frame: TickFrame) => {
    camera.updateMatrixWorld(),
      this.updateShouldRenderLayers(),
      this.shouldRenderLayers &&
        (this.prerenderables.fire?.render(frame),
        this.prerenderables.fluidSimulation?.render(frame),
        this.prerenderables.reflector?.render(frame),
        this.renderLayers(camera, this.rt)),
      setProvider(RenderingPipeline.screenMapProvider, this.rt.texture),
      this.bloom.render(this.renderer, this.#composerMesh, camera),
      (this.#composerMesh.material = this.#composerMaterial),
      this.renderer.setRenderTarget(null),
      this.renderer.render(this.#composerMesh, camera);
  };
}

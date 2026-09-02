// Engine core plumbing — faithful ports from _pretty/CbdjwYMp.pretty.js:
// EventDispatcher (FB/ae L15013-15045), uniform registry (Ml/$e/ke
// L49758-49765), autoGui shader-uniform injection (ri/Tp L49766-49790),
// springs (Di/Ao L49832-49893), Tick (dt L50203-50245), Viewport (Sn
// L50246-50280). Comments cite pretty line numbers.
import { Vector2, Vector4 } from "three";

// ---------------------------------------------------------------- events (FB)
type Listener = (...args: any[]) => void;
class EventDispatcher {
  #listeners: Record<string, Listener[]> = {};
  #depth: Record<string, number> = {};
  #deferredOff: Record<string, Listener[]> = {};
  on = (event: string, listener: Listener) => {
    (this.#listeners[event] ||= []).push(listener);
  };
  off = (event: string, listener: Listener) => {
    // During dispatch, removal is deferred until the event unwinds (L15021).
    if (event in this.#depth && this.#depth[event] > 0) {
      (this.#deferredOff[event] ||= []).push(listener);
      return;
    }
    this.#remove(event, listener);
  };
  #remove = (event: string, listener: Listener) => {
    const list = this.#listeners[event];
    if (list) {
      const i = list.indexOf(listener);
      if (i !== -1) list.splice(i, 1);
    }
  };
  dispatch = (event: string, ...args: any[]) => {
    this.#depth[event] = event in this.#depth ? this.#depth[event] + 1 : 1;
    this.#listeners[event]?.forEach((l) => l(...args));
    this.#depth[event]--;
    if (this.#depth[event] === 0 && event in this.#deferredOff && this.#deferredOff[event].length > 0) {
      this.#deferredOff[event].forEach((l) => this.#remove(event, l));
      delete this.#deferredOff[event];
    }
  };
}
export const events = new EventDispatcher();

// ---------------------------------------------------- uniform registry (Ml)
export const registry: Record<string, { value: any }> = {};
// $e — get-or-create provider (L49759)
export const getProvider = (name: string, initial?: any) => {
  if (!(name in registry)) {
    if (initial === undefined) initial = 0;
    registry[name] = { value: initial };
  }
  return registry[name];
};
// ke — set provider value (L49763)
export const setProvider = (name: string, value: any) => {
  if (name in registry) registry[name].value = value;
  else registry[name] = { value };
  return registry[name];
};

// ------------------------------------------- autoGui injection (ri/Tp)
// `uniform float x@G path min max value once? folder?;` (Gy L49766)
const GUI_RE = /uniform\s+(\w+)\s+(\w+)@G\s(.+);/g;
// `uniform float x@Some.provider;` (Qy L49767)
const BIND_RE = /uniform\s+(\w+)\s+(\w+)@(\S+);/g;

interface ShaderLike {
  vertexShader: string;
  fragmentShader: string;
  uniforms: Record<string, { value: any }>;
}
// ri() — L49768-49789. GUI variant: in prod (devMode false) the binder just
// seeds the provider with the declared value (Mi's non-GUI effect).
export const injectUniforms = <T extends ShaderLike>(shader: T): T => {
  const collected: Record<string, { value: any }> = {};
  const onBind = (_m: string, type: string, name: string, binder: string) => {
    collected[name] = getProvider(binder);
    return `uniform ${type} ${name};`;
  };
  const onGui = (_m: string, type: string, name: string, params: string) => {
    const tokens = params.split(" ");
    const binder = tokens[0];
    const value = +tokens[3];
    const once = tokens[4] === "once" || tokens[5] === "once";
    if (tokens.length < 4)
      throw new Error(`autoGui error, check if params are correct: ${params}`);
    if (once && binder in registry) collected[name] = getProvider(binder);
    else collected[name] = binder in registry ? getProvider(binder) : setProvider(binder, value);
    return `uniform ${type} ${name};`;
  };
  shader.vertexShader = shader.vertexShader.replaceAll(GUI_RE, onGui);
  shader.fragmentShader = shader.fragmentShader.replaceAll(GUI_RE, onGui);
  shader.vertexShader = shader.vertexShader.replaceAll(BIND_RE, onBind);
  shader.fragmentShader = shader.fragmentShader.replaceAll(BIND_RE, onBind);
  shader.uniforms = { ...shader.uniforms, ...collected };
  return shader;
};
// C0 — clamp01（L53110-53112）
export const clamp01 = (n: number) => Math.max(0, Math.min(1, n));

// xte / wte / Ks —— 固定种子 LCG（L50571-50583）。全局单例：Env Spots 与
// one.randomize 共用同一序列，序列语义不可拆。
export class Lcg {
  seed: number;
  constructor(seed = 1111111114) {
    this.seed = seed;
  }
  setSeed = (n: number) => {
    this.seed = n;
  };
  getSeed = () => this.seed;
  random = () => (
    (this.seed = (this.seed * 1664525 + 1013904223) % 2 ** 32), (this.seed >>> 0) / 2 ** 32
  );
}
export const lcg = new Lcg();
export const lcgRandom = () => lcg.random();

// X 替身（L56157-56201）：rebuild 由 index.ts 的 Engine facade 承担装配，
// 但 ek/tk/nn/v1/Pt 等移植代码访问的是 X.settings/X.camera/X.pipeline 等
// 静态成员。这里用模块级替身承接，Engine 构造时填充。
export const appSettings: Record<string, any> = { devMode: false };
// X.createdEvent（L56158）—— Jh/lie 的时间线绑定与 tick/raycaster 订阅全挂此事件
export const app: Record<string, any> = { createdEvent: "Root.createdEvent" };

// Tp() — strip annotations without binding (L49790)
export const stripAnnotations = (src: string) =>
  src
    .replaceAll(GUI_RE, (_m, type, name) => `uniform ${type} ${name};`)
    .replaceAll(BIND_RE, (_m, type, name) => `uniform ${type} ${name};`);

// ------------------------------------------------------------- springs (Di/Ao)
export class Spring {
  static collection: Record<string, Spring> = {};
  static array: Spring[] = [];
  static get = (name: string) => Spring.collection[name].value;
  static getV = (name: string) => Spring.collection[name].v;
  static set = (name: string, target: number) => {
    Spring.collection[name].target = target;
  };
  static update = (ds: number) => Spring.array.forEach((s) => s.update(ds));
  name: string;
  damp: number;
  k: number;
  v = 0;
  value: number;
  target: number;
  constructor(name: string, value: number, k = 1, damp = 1) {
    this.name = name;
    this.damp = damp;
    this.k = k;
    this.value = value;
    this.target = value;
    Spring.collection[name] = this;
    Spring.array.push(this);
  }
  update(ds: number) {
    if (this.k === 0 && this.damp === 0) {
      this.value = this.target;
      this.v = 0;
      return;
    }
    const decay = Math.exp(-this.damp * ds);
    const delta = this.target - this.value;
    this.v = (this.v + delta * this.k * ds) * decay;
    this.value += this.v * ds;
  }
  set = (target: number) => {
    this.target = target;
  };
  get = () => this.value;
}
export class SpringVec {
  static collection: Record<string, SpringVec> = {};
  static get = (name: string) => SpringVec.collection[name].springs.map((s) => s.value);
  static set = (name: string, targets: number[]) =>
    SpringVec.collection[name].springs.forEach((s, i) => (s.target = targets[i]));
  name: string;
  springs: Spring[];
  constructor(name: string, values: number[], k = 1, damp = 1) {
    this.name = name;
    this.springs = values.map(
      (v, i) => new Spring(`!__vec${values.length}-${i}@${name}`, v, k, damp),
    );
    SpringVec.collection[name] = this;
  }
  set = (targets: number[]) => {
    this.springs.forEach((s, i) => (s.target = targets[i]));
  };
  get = () => this.springs.map((s) => s.value);
}

// ------------------------------------------------------------------- Tick (dt)
export class Tick {
  static rawEvent = "Tick.raw";
  static event = "Tick";
  static beforeRenderEvent = "Tick.beforeRender";
  static afterRenderEvent = "Tick.afterRender";
  static timeProvider = "Tick.time";
  static secondsProvider = "Tick.seconds";
  static deltaSecondsProvider = "Tick.deltaSeconds";
  static #id = 0;
  static get id() {
    return Tick.#id;
  }
  prevTime: number | undefined;
  render: (frame: TickFrame) => void;
  constructor(render: (frame: TickFrame) => void) {
    this.render = render;
    setProvider(Tick.timeProvider, 0);
    setProvider(Tick.secondsProvider, 0);
    setProvider(Tick.deltaSecondsProvider, 0);
    events.on(Tick.rawEvent, this.onAnimFrame);
  }
  onAnimFrame = (time: number) => {
    if (this.prevTime === undefined) this.prevTime = time;
    // dt clamped to a 30fps floor (L50236)
    const dt = Math.min(1000 / 30, time - this.prevTime);
    const ds = dt * 0.001;
    const seconds = time * 0.001;
    this.prevTime = time;
    const frame: TickFrame = { time, dt, seconds, ds };
    setProvider(Tick.timeProvider, time);
    setProvider(Tick.secondsProvider, seconds);
    setProvider(Tick.deltaSecondsProvider, ds);
    Tick.#id++;
    Spring.update(ds);
    events.dispatch(Tick.event, frame);
    events.dispatch(Tick.beforeRenderEvent, frame);
    this.render(frame);
    events.dispatch(Tick.afterRenderEvent, frame);
  };
}
export interface TickFrame {
  time: number;
  dt: number;
  seconds: number;
  ds: number;
}

// --------------------------------------------------------------- Viewport (Sn)
export class Viewport {
  static resizeEvent = "Viewport.resize";
  static resolutionProvider = "Viewport.resolution";
  static aspectRatioProvider = "Viewport.aspectRatio";
  static aspectRatioV2Provider = "Viewport.aspectRatioV2";
  static pixelSizeProvider = "Viewport.pixelSize";
  container: HTMLElement = document.body;
  x = 0;
  y = 0;
  v2 = new Vector2();
  iv2 = new Vector2();
  v4 = new Vector4();
  aspectRatio = 1;
  aspectRatioV2 = new Vector2();
  dpr = 2;
  #timer: ReturnType<typeof setTimeout> | null = null;
  constructor() {
    this.updateDPR();
    setProvider(Viewport.resolutionProvider, this.v2);
    setProvider(Viewport.aspectRatioProvider, this.aspectRatio);
    setProvider(Viewport.aspectRatioV2Provider, this.aspectRatioV2);
    setProvider(Viewport.pixelSizeProvider, this.iv2);
    window.addEventListener("resize", this.debounceResize);
  }
  updateDPR = () => {
    this.dpr = Math.min(2, window.devicePixelRatio);
    this.update();
  };
  setContainer = (el: HTMLElement) => {
    this.container = el;
    this.update();
  };
  debounceResize = () => {
    if (this.#timer !== null) clearTimeout(this.#timer);
    this.#timer = setTimeout(this.update, 333);
  };
  update = () => {
    this.#timer = null;
    const el = this.container as any;
    this.x = this.v2.x = this.v4.x = el.clientWidth || el.innerWidth || 0;
    this.y = this.v2.y = this.v4.y = el.clientHeight || el.innerHeight || 0;
    this.aspectRatio = this.x / this.y;
    setProvider(Viewport.aspectRatioProvider, this.aspectRatio);
    this.aspectRatioV2.x = this.aspectRatio > 1 ? this.aspectRatio : 1;
    this.aspectRatioV2.y = this.aspectRatio > 1 ? 1 : this.y / this.x;
    this.iv2.x = this.v4.z = 1 / this.x;
    this.iv2.y = this.v4.w = 1 / this.y;
    events.dispatch(Viewport.resizeEvent, this);
  };
}

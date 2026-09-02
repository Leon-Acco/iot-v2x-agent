// 冰晶宿主 - Plan B assembly (no timeline, hand TRS, container pointer)
import {
  Box3,
  BufferGeometry,
  Float32BufferAttribute,
  CanvasTexture,
  DoubleSide,
  Group,
  Line,
  LineBasicMaterial,
  Points,
  PointsMaterial,
  RingGeometry,
  SphereGeometry,
  TorusGeometry,
  Color,
  EquirectangularReflectionMapping,
  Mesh,
  MeshBasicMaterial,
  PerspectiveCamera,
  PlaneGeometry,
  Vector2,
  Vector3,
  Vector4,
} from "three";
import {
  app,
  appSettings,
  events,
  getProvider,
  setProvider,
  Tick,
  Viewport,
  type TickFrame,
} from "./core";
import { Assets } from "./assets";
import { RenderingPipeline } from "./pipeline";
import { LayerController } from "./layerController";
import { Performance } from "./performance";
import { Pointer } from "./pointer";
import { BlueNoiseMap, ColorsMap } from "./scene/glass/colorMaps";
import {
  BACK_SAMPLES_COUNT,
  CRYSTAL_DEFAULTS,
  CRYSTAL_HOVERS,
  FRONT_SAMPLES_COUNT,
  HYPER_SAMPLES_COUNT,
  LOW_FRONT_SAMPLES_COUNT,
} from "./scene/glass/glassConfig";
import { Crystals, Project } from "./scene/crystals";
import { WaterBg } from "./waterBg";
import { makeNodeIconTexture } from "./crystalIcons";
import { FluidSim } from "./fluidSim";

// smoked noomo look: dark glass body, contained white icon core
const DST_CRYSTAL = {
  ...CRYSTAL_DEFAULTS,
  envReflection: 0.6,
  colorFactor: 1.0,
  maxColorValue: 6,
};

interface LayoutItem {
  p: [number, number, number];
  r: number;
  s: number;
  floatAmp: number;
  floatFreq: number;
  rotSpeed: number;
}

// hand-placed layout replacing the Blender-baked timeline TRS
const LAYOUT: LayoutItem[] = [
  // main AI core node, left-center
  { p: [-1.2, 0.15, -0.2], r: 0.6, s: 1.3, floatAmp: 0.14, floatFreq: 0.95, rotSpeed: 0.14 },
  // satellites: left-top / right-top / left-bottom / right-bottom (tight cluster)
  { p: [-2.9, 1.15, -0.9], r: 1.8, s: 0.55, floatAmp: 0.10, floatFreq: 0.85, rotSpeed: -0.11 },
  { p: [0.7, 1.35, -0.8], r: 2.4, s: 0.6, floatAmp: 0.12, floatFreq: 1.06, rotSpeed: 0.12 },
  { p: [-2.8, -1.1, -0.4], r: 3.2, s: 0.5, floatAmp: 0.09, floatFreq: 0.78, rotSpeed: -0.09 },
  { p: [0.5, -1.15, -0.35], r: 4.1, s: 0.55, floatAmp: 0.11, floatFreq: 0.9, rotSpeed: 0.10 },
];

function makeDotTexture() {
  const c = document.createElement("canvas");
  c.width = c.height = 32;
  const ctx = c.getContext("2d")!;
  const g = ctx.createRadialGradient(16, 16, 1, 16, 16, 15);
  g.addColorStop(0, "rgba(255, 255, 255, 1)");
  g.addColorStop(0.6, "rgba(255, 255, 255, 0.6)");
  g.addColorStop(1, "rgba(255, 255, 255, 0)");
  ctx.fillStyle = g;
  ctx.fillRect(0, 0, 32, 32);
  return new CanvasTexture(c);
}

function makeGlowTexture() {
  const c = document.createElement("canvas");
  c.width = c.height = 128;
  const ctx = c.getContext("2d")!;
  const g = ctx.createRadialGradient(64, 64, 4, 64, 64, 62);
  g.addColorStop(0, "rgba(212, 160, 23, 0.32)");
  g.addColorStop(0.55, "rgba(212, 160, 23, 0.11)");
  g.addColorStop(1, "rgba(212, 160, 23, 0)");
  ctx.fillStyle = g;
  ctx.fillRect(0, 0, 128, 128);
  return new CanvasTexture(c);
}

function makeShadowTexture() {
  const c = document.createElement("canvas");
  c.width = c.height = 128;
  const ctx = c.getContext("2d")!;
  const g = ctx.createRadialGradient(64, 64, 4, 64, 64, 62);
  g.addColorStop(0, "rgba(22, 48, 42, 0.50)");
  g.addColorStop(0.55, "rgba(22, 48, 42, 0.16)");
  g.addColorStop(1, "rgba(22, 48, 42, 0)");
  ctx.fillStyle = g;
  ctx.fillRect(0, 0, 128, 128);
  return new CanvasTexture(c);
}

export class CrystalsHost {
  static hoverEvent = "Sweet3.hover";
  static crystalViewportEvent = "Sweet3.crystalViewport";
  static crystalClickEvent = "Sweet3.crystalClick";
  container: HTMLElement;
  viewport: Viewport;
  pipeline: RenderingPipeline;
  assets: Assets;
  layerController!: LayerController;
  camera!: PerspectiveCamera;
  pointer!: Pointer;
  crystals!: Crystals;
  fluid!: FluidSim;
  tick!: Tick;
  currentHoverId: string | null = null;
  shadows: Mesh[] = [];
  shadowBase: number[] = [];
  ringGroup: Group | null = null;
  orbitGroup: Group | null = null;
  mainCoreMat: MeshBasicMaterial | null = null;
  dust: Points | null = null;
  dustBase: Float32Array | null = null;
  flowCurves: { dot: Points; curve: Vector3[]; speed: number; phase: number }[] = [];
  ripples: { ring: Mesh; mat: MeshBasicMaterial; phase: number }[] = [];
  trails: { mesh: Mesh; speed: number; phase: number }[] = [];
  orbitDots: { dot: Points; r: number; phase: number; speed: number }[] = [];
  links: { line: Line; dot: Points; target: number }[] = [];
  private tmpC = new Vector3();
  private rippleSlot = 0;
  private lastRippleAt = 0;
  private lastRippleUv = new Vector2(0.5, 0.5);
  private inkCycle = 0;
  private inkDist = 0;
  private inkPalette = [
    new Color(0x0f8a6a),
    new Color(0x2fa37c),
    new Color(0x12b5a5),
    new Color(0x4fd8e8),
    new Color(0x5fc3a5),
    new Color(0x1b9ae4),
    new Color(0x0f8a6a),
  ];
  private running = false;
  private rafId = 0;
  private destroyed = false;

  constructor(container: HTMLElement) {
    this.container = container;
    Object.assign(appSettings, {
      devMode: false,
      hyperSamplesCount: HYPER_SAMPLES_COUNT,
      backSamplesCount: BACK_SAMPLES_COUNT,
      frontSamplesCount: FRONT_SAMPLES_COUNT,
      lowFrontSamplesCount: LOW_FRONT_SAMPLES_COUNT,
      crystal: DST_CRYSTAL,
      crystalHovers: CRYSTAL_HOVERS,
    });
    // seed Glass.* providers so material construction binds sensible values
    for (const key of Object.keys(DST_CRYSTAL) as (keyof typeof DST_CRYSTAL)[]) {
      setProvider("Glass." + key, (DST_CRYSTAL as any)[key]);
    }
    setProvider("Glass.useTransmittance", 0);
    setProvider("Glass.color", new Color(16777215));
    this.viewport = new Viewport();
    app.viewport = this.viewport;
    this.pipeline = new RenderingPipeline(this.viewport, () => {});
    app.pipeline = this.pipeline;
    // canvas bg matches the login page bg for a seamless blend
    this.pipeline.background = new Color(0x04100c);
    // kill blocky glow artifacts on the black background
    setProvider("post.halo", 0);
    setProvider("post.bloom.power", 0.06);
    setProvider("post.bloom.filter.threshold", 1.3);
    this.assets = new Assets(() => this.pipeline.renderer);
    app.assetsManager = this.assets;
  }

  on = (event: string, handler: (...args: any[]) => void) => {
    events.on(event, handler);
  };

  load = async () => {
    this.viewport.setContainer(this.container);
    this.pipeline.setContainer(this.container);
    events.on(Viewport.resizeEvent, this.pipeline.onResize);
    await this.assets.loadAll();

    this.layerController = new LayerController();
    app.layerController = this.layerController;
    // GPU fluid sim (Navier-Stokes) feeding the background dye texture
    this.fluid = new FluidSim(this.pipeline.renderer);
    setProvider("Fluid.dye", this.fluid.dyeTexture);
    events.on(Tick.beforeRenderEvent, (frame: TickFrame) => {
      this.fluid.step(frame.ds);
    });
    // ambient opening splats so the scene is alive before first mouse move
    for (let i = 0; i < 2; i++) {
      const c = this.inkPalette[i % this.inkPalette.length];
      this.fluid.splat(
        0.3 + Math.random() * 0.4, 0.35 + Math.random() * 0.3,
        (Math.random() - 0.5) * 500, (Math.random() - 0.5) * 500,
        c.r * 0.18, c.g * 0.18, c.b * 0.18, 1.78,
      );
    }
    // water surface as the bottom-most render layer
    events.dispatch(LayerController.addLayerEvent, new WaterBg());

    this.camera = new PerspectiveCamera(25, this.viewport.aspectRatio, 0.1, 500);
    this.camera.position.set(0, 0.15, 9);
    this.camera.lookAt(0, 0.1, 0);
    app.camera = this.camera;
    events.on(Viewport.resizeEvent, () => {
      this.camera.aspect = this.viewport.aspectRatio;
      this.camera.updateProjectionMatrix();
    });

    // env HDR -> Env.map provider (equirect)
    const envTex = this.assets.get("env");
    envTex.mapping = EquirectangularReflectionMapping;
    setProvider("Env.map", envTex);
    new ColorsMap();
    new BlueNoiseMap();

    this.pointer = new Pointer();
    app.pointer = this.pointer;

    this.crystals = new Crystals();
    // drop the crystals outside the hand-placed layout (login card zone)
    this.crystals.projects.slice(LAYOUT.length).forEach((proj) => {
      events.dispatch(LayerController.removeLayerEvent, proj.mesh);
      proj.mesh.disposeFrostedCube();
    });
    this.crystals.projects = this.crystals.projects.slice(0, LAYOUT.length);
    // hand TRS + normalize each crystal to a target size
    const bb = new Box3();
    const size = new Vector3();
    this.crystals.projects.forEach((proj, i) => {
      const L = LAYOUT[i];
      proj.mesh.position.set(L.p[0], L.p[1], L.p[2]);
      proj.mesh.updateMatrixWorld(true);
      bb.setFromObject(proj.mesh);
      bb.getSize(size);
      const maxDim = Math.max(size.x, size.y, size.z) || 1;
      const k = (1.7 * L.s) / maxDim;
      proj.mesh.scale.setScalar(k);
      proj.dummy.rotation.set(0, L.r, 0);
      proj.mesh.geometry.computeBoundingBox();
      const geoCenter = proj.mesh.geometry.boundingBox!.getCenter(new Vector3());
      proj.mesh.userData.geoCenterLocal = geoCenter;
      // vehicle-themed node icon billboard inside the crystal
      const iconSize = 0.45 * maxDim;
      const plane = new Mesh(
        new PlaneGeometry(iconSize, iconSize),
        new MeshBasicMaterial({
          map: makeNodeIconTexture(i),
          transparent: true,
          depthWrite: false,
          side: DoubleSide,
        }),
      );
      const bbC = bb.getCenter(new Vector3());
      plane.position.copy(bbC).sub(proj.mesh.position).divideScalar(k);
      const holder = new Group() as any;
      holder.userData.billboard = true;
      holder.add(plane);
      if (i === 0) {
        this.mainCoreMat = new MeshBasicMaterial({ color: 0xffffff, transparent: true, opacity: 0.32 });
        const core = new Mesh(new SphereGeometry(maxDim * 0.09, 16, 16), this.mainCoreMat);
        core.position.copy(plane.position);
        holder.add(core);
      }
      proj.mesh.insideMesh = holder;
    });
    // partial orbit rings around the main crystal (AI core node)
    const mainWorldSize = 1.7 * LAYOUT[0].s;
    this.ringGroup = new Group();
    const arcs = [Math.PI * 1.3, Math.PI * 1.45];
    const tilts: [number, number, number][] = [
      [1.15, 0.2, 0.3],
      [1.35, -0.4, -0.2],
    ];
    arcs.forEach((arc, ri) => {
      const ring = new Mesh(
        new TorusGeometry(mainWorldSize * (0.66 + ri * 0.12), 0.004, 8, 96, arc),
        new MeshBasicMaterial({ color: 0x12b5a5, transparent: true, opacity: 0.22 - ri * 0.05 }),
      );
      ring.rotation.set(tilts[ri][0], tilts[ri][1], tilts[ri][2]);
      this.ringGroup!.add(ring);
    });
    this.ringGroup.frustumCulled = false;
    events.dispatch(LayerController.addLayerEvent, this.ringGroup);
    // thin data links from the main crystal to each satellite
    const linkGroup = new Group();
    const linkMat = new LineBasicMaterial({ color: 0x0f8a6a, transparent: true, opacity: 0.26 });
    const dotMat = new PointsMaterial({ color: 0x12b5a5, size: 9, sizeAttenuation: false, map: makeDotTexture(), transparent: true, opacity: 0.9, depthWrite: false });
    for (let li = 1; li < LAYOUT.length; li++) {
      const line = new Line(new BufferGeometry().setFromPoints([new Vector3(), new Vector3()]), linkMat);
      const dot = new Points(new BufferGeometry().setFromPoints([new Vector3()]), dotMat);
      line.frustumCulled = false;
      dot.frustumCulled = false;
      linkGroup.add(line);
      linkGroup.add(dot);
      this.links.push({ line, dot, target: li });
    }
    linkGroup.frustumCulled = false;
    events.dispatch(LayerController.addLayerEvent, linkGroup);
    // grand orbits chaining every crystal (thin tilted ellipses + travelers)
    this.orbitGroup = new Group();
    const orbitMat = new LineBasicMaterial({ color: 0x5fc3a5, transparent: true, opacity: 0.15 });
    const ellipse = (r: number) => {
      const pts: Vector3[] = [];
      for (let i = 0; i <= 128; i++) {
        const a = (i / 128) * Math.PI * 2;
        pts.push(new Vector3(Math.cos(a) * r, Math.sin(a) * r, 0));
      }
      return new BufferGeometry().setFromPoints(pts);
    };
    const orbit1 = new Line(ellipse(2.3), orbitMat);
    orbit1.rotation.set(1.25, 0.1, 0.3);
    this.orbitGroup.add(orbit1);
    this.orbitDots = [];
    for (let i = 0; i < 2; i++) {
      const d = new Points(new BufferGeometry().setFromPoints([new Vector3()]), dotMat);
      d.frustumCulled = false;
      orbit1.add(d);
      this.orbitDots.push({ dot: d, r: 2.3, phase: i * 3.1, speed: 0.3 + i * 0.1 });
    }
    this.orbitGroup.frustumCulled = false;
    events.dispatch(LayerController.addLayerEvent, this.orbitGroup);
    // flowing data curves arcing across the bottom and sides
    const curveMat = new LineBasicMaterial({ color: 0x2fa37c, transparent: true, opacity: 0.22 });
    const curveDefs = [
      { base: -1.75, amp: 0.35, freq: 0.8, bend: 0.010 },
      { base: -1.45, amp: 0.5, freq: 0.55, bend: 0.016 },
      { base: -0.9, amp: 0.7, freq: 0.4, bend: 0.030 },
    ];
    curveDefs.forEach((cd, ci) => {
      const pts: Vector3[] = [];
      for (let i = 0; i <= 90; i++) {
        const x = -4.5 + (i / 90) * 9;
        pts.push(new Vector3(x, cd.base + Math.sin(x * cd.freq + ci * 1.7) * cd.amp * 0.35 + x * x * cd.bend, -1.2));
      }
      const line = new Line(new BufferGeometry().setFromPoints(pts), curveMat);
      line.frustumCulled = false;
      events.dispatch(LayerController.addLayerEvent, line);
      for (let k = 0; k < 2; k++) {
        const d = new Points(new BufferGeometry().setFromPoints([new Vector3()]), dotMat);
        d.frustumCulled = false;
        events.dispatch(LayerController.addLayerEvent, d);
        this.flowCurves.push({ dot: d, curve: pts, speed: 0.05 + ci * 0.02, phase: k * 0.5 + ci * 0.23 });
      }
    });
    // faint smart-city silhouette at bottom-right
    const sc = document.createElement("canvas");
    sc.width = 256; sc.height = 96;
    const sctx = sc.getContext("2d")!;
    let sx = 4;
    while (sx < 244) {
      const bw = 8 + Math.random() * 16;
      const bh = 20 + Math.random() * 62;
      sctx.fillStyle = "rgba(15, 138, 106, 0.85)";
      sctx.fillRect(sx, 96 - bh, bw, bh);
      sx += bw + 3 + Math.random() * 7;
    }
    const cityTex = new CanvasTexture(sc);
    const city = new Mesh(
      new PlaneGeometry(3.4, 1.28),
      new MeshBasicMaterial({ map: cityTex, transparent: true, opacity: 0.22, depthWrite: false }),
    );
    city.position.set(2.5, -1.35, -1.6);
    city.frustumCulled = false;
    events.dispatch(LayerController.addLayerEvent, city);
    // vehicle light trails sweeping along the bottom
    for (let i = 0; i < 3; i++) {
      const tm = new Mesh(
        new PlaneGeometry(2.4, 0.018),
        new MeshBasicMaterial({ color: 0x35bdb0, transparent: true, opacity: 0.5, depthWrite: false }),
      );
      tm.position.set(-6, -1.75 + i * 0.09, -0.6);
      tm.frustumCulled = false;
      events.dispatch(LayerController.addLayerEvent, tm);
      this.trails.push({ mesh: tm, speed: 0.55 + i * 0.18, phase: i * 0.33 });
    }
    // energy discs + expanding ripples under each crystal
    const glowTexE = makeGlowTexture();
    this.crystals.projects.forEach((proj, i) => {
      const L = LAYOUT[i];
      const size = 1.7 * L.s;
      const disc = new Mesh(
        new PlaneGeometry(1, 1),
        new MeshBasicMaterial({ map: glowTexE, transparent: true, depthWrite: false, opacity: i === 0 ? 0.55 : 0.35 }),
      );
      disc.position.set(L.p[0], L.p[1] - size * 0.72, L.p[2]);
      disc.rotation.x = -Math.PI / 2;
      disc.scale.set(size * 1.5, size * 1.5, 1);
      disc.frustumCulled = false;
      events.dispatch(LayerController.addLayerEvent, disc);
      const rmat = new MeshBasicMaterial({ color: 0xd4a017, transparent: true, opacity: 0.3, side: DoubleSide, depthWrite: false });
      const rip = new Mesh(new RingGeometry(0.45, 0.5, 48), rmat);
      rip.position.copy(disc.position);
      rip.rotation.x = -Math.PI / 2;
      rip.frustumCulled = false;
      events.dispatch(LayerController.addLayerEvent, rip);
      this.ripples.push({ ring: rip, mat: rmat, phase: i * 0.2 });
    });
    // ambient dust: tiny motes drifting slowly through the data space
    const dustCount = 130;
    const dustArr = new Float32Array(dustCount * 3);
    this.dustBase = new Float32Array(dustCount * 2);
    for (let i = 0; i < dustCount; i++) {
      const bx = (Math.random() - 0.5) * 8;
      const by = (Math.random() - 0.5) * 4;
      dustArr[i * 3] = bx;
      dustArr[i * 3 + 1] = by;
      dustArr[i * 3 + 2] = -1.2 + Math.random() * 1.6;
      this.dustBase[i * 2] = bx;
      this.dustBase[i * 2 + 1] = by;
    }
    const dustGeo = new BufferGeometry();
    dustGeo.setAttribute("position", new Float32BufferAttribute(dustArr, 3));
    this.dust = new Points(
      dustGeo,
      new PointsMaterial({
        color: 0x2fa37c, size: 3, sizeAttenuation: false, map: makeDotTexture(),
        transparent: true, opacity: 0.35, depthWrite: false,
      }),
    );
    this.dust.frustumCulled = false;
    events.dispatch(LayerController.addLayerEvent, this.dust);
    // soft contact shadows ground the floating crystals visually
    const shadowTex = makeShadowTexture();
    const shadowGeo = new PlaneGeometry(1, 1);
    const shadowMat = new MeshBasicMaterial({ map: shadowTex, transparent: true, depthWrite: false });
    this.shadows = ([] as Mesh[]).concat(this.crystals.projects.length < 0 ? [] : []) ;
    /* shadows disabled on the dark theme
    this.shadows = this.crystals.projects.map((proj, i) => {
      const L = LAYOUT[i];
      const w = 1.7 * L.s * 1.9;
      const sm = new Mesh(shadowGeo, shadowMat);
      sm.position.set(L.p[0], -2.0, L.p[2]);
      sm.rotation.x = -Math.PI / 2;
      sm.scale.set(w, w * 0.72, 1);
      sm.frustumCulled = false;
      this.shadowBase[i] = w;
      return sm;
    });
    // insert shadows right after the water layer, before crystals
    this.layerController.layers.splice(1, 0, ...this.shadows);
    */
    // idle float runs before the projects own onTick (registration order)
    events.on(Tick.event, this.onIdleTick);
    events.dispatch(app.createdEvent);

    events.on(Project.hoverEvent, this.onProjectHover);
    events.on(Project.viewportEvent, this.onProjectViewport);
    events.on(Pointer.clickEvent, () => {
      if (this.currentHoverId) events.dispatch(CrystalsHost.crystalClickEvent, this.currentHoverId);
    });

    // container-relative pointer -> raycaster hover
    window.addEventListener("mousemove", this.onRawMove);
    window.addEventListener("mousedown", this.onRawDown);
    window.addEventListener("mouseup", this.onRawUp);
    window.addEventListener("touchmove", this.onRawTouchMove, { passive: true });
    window.addEventListener("touchstart", this.onRawTouchStart, { passive: true });
    window.addEventListener("touchend", this.onRawUp);

    this.startLoop();
    // performance tiers: cut rendered pixels ~70% for a smooth frame rate
    events.dispatch(Performance.msaaEvent, false);
    events.dispatch(Performance.normalDPREvent, false);
    events.dispatch(Performance.screenPaddingEvent, false);
  };

  onIdleTick = (frame: TickFrame) => {
    const t = frame.seconds;
    this.crystals.projects.forEach((proj, i) => {
      const L = LAYOUT[i];
      proj.mesh.position.y = L.p[1] + Math.sin(t * L.floatFreq + i * 1.7) * L.floatAmp;
      proj.dummy.rotation.y = L.r + t * L.rotSpeed;
    });
    // animate the data-network pieces
    if (this.ringGroup) {
      const m0 = this.tmpC
        .copy(this.crystals.projects[0].mesh.userData.geoCenterLocal)
        .applyMatrix4(this.crystals.projects[0].mesh.matrixWorld);
      this.ringGroup.position.copy(m0);
      this.ringGroup.rotation.y = t * 0.05;
      this.ringGroup.rotation.z = t * 0.03;
      this.links.forEach((lk, li) => {
        const m1 = this.tmpC
          .copy(this.crystals.projects[lk.target].mesh.userData.geoCenterLocal)
          .applyMatrix4(this.crystals.projects[lk.target].mesh.matrixWorld);
        const lp = lk.line.geometry.getAttribute("position") as any;
        lp.setXYZ(0, m0.x, m0.y, m0.z);
        lp.setXYZ(1, m1.x, m1.y, m1.z);
        lp.needsUpdate = true;
        const f = (t * 0.12 + li * 0.27) % 1;
        const dp = lk.dot.geometry.getAttribute("position") as any;
        dp.setXYZ(0, m0.x + (m1.x - m0.x) * f, m0.y + (m1.y - m0.y) * f, m0.z + (m1.z - m0.z) * f);
        dp.needsUpdate = true;
      });
    }
    if (this.orbitGroup) {
      const oc = this.tmpC
        .copy(this.crystals.projects[0].mesh.userData.geoCenterLocal)
        .applyMatrix4(this.crystals.projects[0].mesh.matrixWorld);
      this.orbitGroup.position.copy(oc);
      this.orbitGroup.rotation.y = t * 0.04;
      this.orbitDots.forEach((od) => {
        const a = t * od.speed + od.phase;
        od.dot.position.set(Math.cos(a) * od.r, Math.sin(a) * od.r, 0);
      });
    }
    // flow-curve travelers
    this.flowCurves.forEach((fc) => {
      const f = (t * fc.speed + fc.phase) % 1;
      const idx = Math.min(fc.curve.length - 1, Math.floor(f * (fc.curve.length - 1)));
      fc.dot.position.copy(fc.curve[idx]);
    });
    // light trails sweep left -> right with fade at the ends
    this.trails.forEach((tr) => {
      const f = (t * tr.speed * 0.2 + tr.phase) % 1;
      tr.mesh.position.x = -5.5 + f * 11;
      (tr.mesh.material as MeshBasicMaterial).opacity = 0.5 * Math.sin(f * Math.PI);
    });
    // ripples expand and fade under each crystal
    this.ripples.forEach((rp, i) => {
      const f = (t * 0.4 + rp.phase) % 1;
      const base = 1.7 * LAYOUT[i].s;
      const sc2 = base * (0.5 + f * 1.4);
      rp.ring.scale.set(sc2, sc2, 1);
      rp.mat.opacity = 0.32 * (1 - f);
    });
    if (this.dust && this.dustBase) {
      const dpos = this.dust.geometry.getAttribute("position") as any;
      const n = dpos.count;
      for (let i = 0; i < n; i++) {
        dpos.setX(i, this.dustBase[i * 2] + Math.sin(t * 0.20 + i * 1.3) * 0.18);
        dpos.setY(i, this.dustBase[i * 2 + 1] + Math.sin(t * 0.30 + i * 0.9) * 0.28);
      }
      dpos.needsUpdate = true;
    }
    if (this.mainCoreMat) {
      this.mainCoreMat.opacity = 0.35 + 0.45 * (0.5 + 0.5 * Math.sin(t * 1.9));
    }
    const camQ = this.camera.quaternion;
    this.crystals.projects.forEach((proj) => {
      const im = proj.mesh.insideMesh as any;
      if (im && im.userData && im.userData.billboard) im.quaternion.copy(camQ);
    });
    this.shadows.forEach((sm, i) => {
      const pulse = 1 - Math.sin(t * 0.9 + i * 1.7) * 0.09;
      sm.scale.set(this.shadowBase[i] * pulse, this.shadowBase[i] * 0.72 * pulse, 1);
    });
  };

  onRawMove = (e: MouseEvent) => {
    const rect = this.container.getBoundingClientRect();
    events.dispatch(Pointer.rawEvent, { x: e.clientX - rect.left, y: e.clientY - rect.top });
    // feed the fluid sim with a velocity + dye splat
    const uvx = (e.clientX - rect.left) / Math.max(rect.width, 1);
    const uvy = 1 - (e.clientY - rect.top) / Math.max(rect.height, 1);
    const dx = uvx - this.lastRippleUv.x;
    const dy = uvy - this.lastRippleUv.y;
    this.lastRippleUv.set(uvx, uvy);
    if (!this.fluid || dx * dx + dy * dy < 1e-10) return;
    // advance the palette by stroke distance -> smooth rainbow gradient
    this.inkDist += Math.sqrt(dx * dx + dy * dy);
    if (this.inkDist > 0.055) {
      this.inkDist = 0;
      this.inkCycle = (this.inkCycle + 1) % this.inkPalette.length;
    }
    const c = this.inkPalette[this.inkCycle];
    const aspect = rect.width / Math.max(rect.height, 1);
    this.fluid.splat(uvx, uvy, dx * aspect * 3000, dy * 3000, c.r * 0.16, c.g * 0.16, c.b * 0.16, aspect);
  };
  onRawDown = () => {
    events.dispatch(Pointer.rawDownEvent);
  };
  onRawUp = () => {
    events.dispatch(Pointer.rawUpEvent);
  };
  onRawTouchStart = (e: TouchEvent) => {
    this.onRawTouchMove(e), this.onRawDown();
  };
  onRawTouchMove = (e: TouchEvent) => {
    const t = e.touches[0] ?? e.changedTouches[0];
    if (!t) return;
    const rect = this.container.getBoundingClientRect();
    events.dispatch(Pointer.rawEvent, { x: t.clientX - rect.left, y: t.clientY - rect.top });
  };

  onProjectHover = ({ id, isHovered }: { id: string; isHovered: boolean }) => {
    if (isHovered && this.currentHoverId !== id) {
      this.currentHoverId = id;
      events.dispatch(CrystalsHost.hoverEvent, id);
    }
    if (!isHovered && this.currentHoverId === id) {
      this.currentHoverId = null;
      events.dispatch(CrystalsHost.hoverEvent, null);
    }
  };
  onProjectViewport = ({ id, ndc }: { id: string; ndc: { x: number; y: number } }) => {
    events.dispatch(CrystalsHost.crystalViewportEvent, {
      id,
      position: { x: ndc.x * 0.5 + 0.5, y: ndc.y * 0.5 + 0.5 },
    });
  };

  startLoop = () => {
    if (this.running) return;
    this.running = true;
    this.tick = new Tick((frame: TickFrame) => {
      this.pipeline.render(this.camera, frame);
    });
    const loop = (t: number) => {
      if (this.destroyed) return;
      if (!document.hidden) events.dispatch(Tick.rawEvent, t);
      this.rafId = requestAnimationFrame(loop);
    };
    this.rafId = requestAnimationFrame(loop);
  };

  // stop frame loop, detach listeners, dispose materials and canvas
  destroy = () => {
    this.destroyed = true;
    cancelAnimationFrame(this.rafId);
    window.removeEventListener("mousemove", this.onRawMove);
    window.removeEventListener("mousedown", this.onRawDown);
    window.removeEventListener("mouseup", this.onRawUp);
    window.removeEventListener("touchmove", this.onRawTouchMove);
    window.removeEventListener("touchstart", this.onRawTouchStart);
    window.removeEventListener("touchend", this.onRawUp);
    if (this.crystals) {
      this.crystals.projects.forEach((proj) => {
        events.off(Tick.event, proj.onTick);
        proj.mesh.disposeFrostedCube();
      });
      this.crystals.iconMaterial.dispose();
    }
    events.off(Tick.event, this.onIdleTick);
    const canvas = this.pipeline.renderer.domElement;
    this.pipeline.renderer.dispose();
    if (canvas.parentElement === this.container) this.container.removeChild(canvas);
  };
}

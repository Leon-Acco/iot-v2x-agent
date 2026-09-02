// 冰晶层 —— 逐字移植 bundle：
//   sie（IconMaterial，L54862-54869）   假 matcap 图标材质，渲进 backRT
//   oie（setupFrostedCube，L54871-54902） 磨砂立方体装配（isFrostedCube 双 pass）
//   aie（SpringProvider，L54903-54910）   Spring 子类，update 后回写 provider
//   Jh（Project，L54911-54990）           每晶 hover 状态机 + 时间线绑定 + NDC 上报
//   lie（Crystals，L54991-55020）         7 晶装配 + Projects_visible 显隐绑定
// 怪癖照抄（REBUILD_PLAN §6）：
//   ⑥ oie 构造后直写 a.defines.USE_DEFAULT_DIST = "1"（值 "1" 非 ""）
//   ⑦ addColorSpring 前后材质 $e 不对称：front $e(s,t)（带初值）/ back $e(s)
//   ⑧ oie 的 tangent 重写无 nk 的 else-warn 分支
// 备注：isFrostedCube 非 isGlassDispersion → 不进 sortedGlassLayers、不走玻璃
// cullSphere 特判，pipeline 零改动（L54897 render 签名与 nn.renderLayers 的
// 三参钩子约定一致）。
import {
  BufferAttribute,
  Color,
  Mesh,
  Object3D,
  ShaderMaterial,
  Vector3,
  type Raycaster,
  type Texture,
} from "three";
import {
  Spring,
  SpringVec,
  Tick,
  app,
  appSettings,
  events,
  getProvider,
  injectUniforms,
  setProvider,
} from "../core";
import { LayerController } from "../layerController";
import { Pointer } from "../pointer";
import { GlassBackMaterial, GlassFrontMaterial } from "./glass/materials";
import { ICON_FRAG, ICON_VERT } from "./glass/shaders";

// sie —— IconMaterial（L54862-54869）。无 ShaderRegistry.add、无 Tp()。
export class IconMaterial extends ShaderMaterial {
  constructor() {
    super(
      injectUniforms({
        vertexShader: ICON_VERT,
        fragmentShader: ICON_FRAG,
      } as any),
    );
    if (appSettings.devMode) this.name = "IconMaterial";
  }
}

// oie 挂到 Mesh 上的附加成员（L54897-54901）
export interface FrostedCubeMesh extends Mesh {
  isFrostedCube: true;
  insideMesh: Mesh | null;
  frontMaterial: GlassFrontMaterial;
  backMaterial: GlassBackMaterial;
  render: (pipeline: any, camera: any, target: any) => void;
  disposeFrostedCube: () => void;
}

// oie —— 磨砂立方体装配（L54871-54902）：
// 1. geometry.clone() 后删 _coverage/_numnonpacked 属性
// 2. tangent 全 w===1 → 重写 itemSize 3（无 nk 的 else-warn 分支，怪癖⑧）
// 3. tk/ek 双材质；back 构造后直写 USE_DEFAULT_DIST="1"（怪癖⑥）
// 4. render 三 pass：backRT 渲 back → backRT 渲 insideMesh（TRS 同步）→ target 渲 front
export function setupFrostedCube(
  cube: Mesh,
  insideMesh: Mesh | null,
  normalAsset: string,
): FrostedCubeMesh {
  const normalMap = app.assetsManager.get(normalAsset) as Texture;
  const geometry = cube.geometry.clone();
  geometry.deleteAttribute("_coverage"), geometry.deleteAttribute("_numnonpacked");
  const tangentAttr = geometry.getAttribute("tangent");
  if (tangentAttr && tangentAttr.itemSize === 4) {
    let allOnes = true;
    for (let i = 0; i < tangentAttr.count; i++)
      if (tangentAttr.getW(i) !== 1) {
        allOnes = false;
        break;
      }
    if (allOnes) {
      const count = tangentAttr.count;
      const array = new Float32Array(count * 3);
      for (let i = 0; i < count; i++)
        (array[i * 3 + 0] = tangentAttr.getX(i)),
          (array[i * 3 + 1] = tangentAttr.getY(i)),
          (array[i * 3 + 2] = tangentAttr.getZ(i));
      geometry.setAttribute(
        "tangent",
        new BufferAttribute(array, 3, tangentAttr.normalized === true),
      );
    }
  }
  const frontMaterial = new GlassFrontMaterial({ normalMap });
  const backMaterial = new GlassBackMaterial({ normalMap });
  (backMaterial.defines as any).USE_DEFAULT_DIST = "1";
  const cube2 = new Mesh(geometry, null) as unknown as FrostedCubeMesh;
  return (
    (cube2.isFrostedCube = true),
    (cube2.insideMesh = insideMesh || null),
    (cube2.frontMaterial = frontMaterial),
    (cube2.backMaterial = backMaterial),
    (cube2.render = function (
      this: FrostedCubeMesh,
      pipeline: any,
      camera: any,
      target: any,
    ) {
      pipeline.renderer.setRenderTarget(pipeline.backRT),
        (this.material = this.backMaterial),
        pipeline.renderer.render(this, camera),
        this.insideMesh !== null &&
          (this.insideMesh.position.copy(this.position),
          (this.insideMesh.userData && this.insideMesh.userData.billboard) ||
            this.insideMesh.rotation.copy(this.rotation),
          this.insideMesh.scale.copy(this.scale),
          pipeline.renderer.render(this.insideMesh, camera)),
        pipeline.renderer.setRenderTarget(target),
        (this.material = this.frontMaterial),
        pipeline.renderer.render(this, camera);
    }),
    (cube2.disposeFrostedCube = function (this: FrostedCubeMesh) {
      this.frontMaterial.dispose(), this.backMaterial.dispose(), this.geometry.dispose();
    }),
    (cube2.material = frontMaterial),
    cube2
  );
}

// aie —— SpringProvider（L54903-54910）：构造即 ke(name, value) 建档，
// 每次 update 后把弹簧当前值回写 provider（uniform 直接引用 provider 对象，
// 无需逐帧触碰材质）。
export class SpringProvider extends Spring {
  constructor(name: string, value: number, k = 1, damp = 1) {
    super(name, value, k, damp), setProvider(name, value);
  }
  override update(ds: number) {
    super.update(ds), setProvider(this.name, this.value);
  }
}

// Jh —— Project（L54911-54990）：单晶 hover 状态机。
// 构造期：traverse 找 Icon/Cube/ConvexHull → oie 装配 → 弹簧建档 → addLayer；
// initialize（X.createdEvent 时）：Et 绑 position/rotation/scale 三条时间线 →
// 订阅 Tick 与 raycaster。
export class Project {
  static hoverEvent = "Project.hover";
  static viewportEvent = "Project.viewport";
  mesh!: FrostedCubeMesh;
  intersectorMesh!: Mesh;
  id: string;
  timeline: string;
  dummy = new Object3D();
  rotationSpring: Spring;
  springProviders: Record<string, SpringProvider> = {};
  colorSprings: Record<string, { springVector: SpringVec; color: Color }> = {};
  hoverSettings: Record<string, any>;
  rotationSign: number;
  ndc = new Vector3();
  prevNDC = new Vector3();
  #isHovered = false;
  constructor({
    id,
    timeline,
    asset,
    normalAsset,
    iconMaterial,
    rotationSign = 1,
    hoverSettings = {},
  }: {
    id: string;
    timeline: string;
    asset: string;
    normalAsset: string;
    iconMaterial: IconMaterial;
    rotationSign?: number;
    hoverSettings?: Record<string, any>;
  }) {
    const gltf = app.assetsManager.get(asset);
    let cube: Mesh | null = null,
      icon: Mesh | null = null;
    gltf.scene.traverse((h: any) => {
      h.isMesh &&
        (h.name === "Icon"
          ? (icon = h)
          : h.name === "Cube"
            ? (cube = h)
            : h.name === "ConvexHull" && (this.intersectorMesh = h));
    }),
      icon !== null && (icon = new Mesh((icon as Mesh).geometry, iconMaterial)),
      (this.mesh = setupFrostedCube(cube!, icon, normalAsset)),
      (this.id = id),
      (this.timeline = timeline),
      (this.rotationSign = rotationSign),
      (this.rotationSpring = new Spring(`project.${id}.rotation`, 0, 20, 3)),
      (this.hoverSettings = hoverSettings),
      Object.keys(hoverSettings).forEach((h) => {
        const d = appSettings.crystal[h];
        d instanceof Color
          ? this.addColorSpring(h, d.clone(), 20, 10)
          : this.addSpringProvider(h, d, 20, 10);
      }),
      events.dispatch(LayerController.addLayerEvent, this.mesh),
      events.on(app.createdEvent, this.initialize);
  }
  initialize = () => {
    
      events.off(app.createdEvent, this.initialize),
      events.on(Tick.event, this.onTick),
      events.on(Pointer.raycasterEvent, this.onRaycaster);
  };
  addSpringProvider = (name: string, value: number, k = 20, damp = 10) => {
    const s = `project.${this.id}.${name}`;
    (this.springProviders[name] = new SpringProvider(s, value, k, damp)),
      (this.mesh.frontMaterial.uniforms[name] = getProvider(s)),
      (this.mesh.backMaterial.uniforms[name] = getProvider(s));
  };
  addColorSpring = (name: string, color: Color, k = 20, damp = 10) => {
    const s = `project.${this.id}.${name}`,
      springVector = new SpringVec(s, [color.r, color.g, color.b], k, damp);
    // 前后 $e 不对称照抄（怪癖⑦）：provider value 即 Color 实例，
    // onTick 里 t.set(i,r,s) 原地改
    (this.mesh.frontMaterial.uniforms[name] = getProvider(s, color)),
      (this.mesh.backMaterial.uniforms[name] = getProvider(s)),
      (this.colorSprings[name] = { springVector, color });
  };
  onTick = () => {
    this.mesh.rotation.copy(this.dummy.rotation),
      this.mesh.rotateOnWorldAxis(this.mesh.up, this.rotationSpring.get()),
      this.mesh.userData.geoCenterLocal
        ? this.ndc
            .copy(this.mesh.userData.geoCenterLocal)
            .applyMatrix4(this.mesh.matrixWorld)
        : this.ndc.copy(this.mesh.position),
      this.ndc.project(app.camera),
      this.ndc.x > -1.5 &&
        this.ndc.x < 1.5 &&
        this.ndc.y > -1.5 &&
        this.ndc.y < 1.5 &&
        this.ndc.manhattanDistanceTo(this.prevNDC) > 1e-4 &&
        events.dispatch(Project.viewportEvent, {
          id: this.id,
          ndc: this.ndc,
        }),
      this.prevNDC.copy(this.ndc),
      Object.values(this.colorSprings).forEach(({ springVector, color }) => {
        const [r, g, b] = springVector.get();
        color.set(r, g, b);
      });
  };
  onRaycaster = (raycaster: Raycaster) => {
    if (!this.mesh.visible) return;
    this.intersectorMesh.matrixWorld.copy(this.mesh.matrixWorld);
    const t = raycaster.intersectObject(this.intersectorMesh, false);
    this.isHovered = t.length > 0;
  };
  set isHovered(value: boolean) {
    this.#isHovered !== value &&
      ((this.#isHovered = value),
      value
        ? (this.rotationSpring.set((15 / 180) * Math.PI * this.rotationSign),
          Object.entries(this.hoverSettings).forEach(([name, value2]) => {
            value2 instanceof Color
              ? this.colorSprings[name].springVector.set([value2.r, value2.g, value2.b])
              : this.springProviders[name].set(value2);
          }))
        : (this.rotationSpring.set(0),
          Object.keys(this.hoverSettings).forEach((name) => {
            const d = appSettings.crystal[name];
            d instanceof Color
              ? this.colorSprings[name].springVector.set([d.r, d.g, d.b])
              : this.springProviders[name].set(d);
          })),
      events.dispatch(Project.hoverEvent, {
        id: this.id,
        isHovered: value,
      }));
  }
}

// lie —— Crystals（L54991-55020）：7 晶装配。crystalN ↔ projectN 一一对应，
// rotationSign = e%2===0?1:-1；initialize 绑 Projects_visible.position.x 显隐。
export class Crystals {
  projects: Project[] = [];
  iconMaterial = new IconMaterial();
  constructor() {
    for (let e = 0; e < appSettings.crystalHovers.length; e++) {
      const t = new Project({
        id: "crystal" + e,
        timeline: "project" + e,
        asset: "crystal" + e,
        normalAsset: "crystalNormal" + e,
        iconMaterial: this.iconMaterial,
        rotationSign: e % 2 === 0 ? 1 : -1,
        hoverSettings: appSettings.crystalHovers[e],
      });
      this.projects.push(t);
    }
    events.on(app.createdEvent, this.initialize);
  }
  initialize = () => {
    this.visible = 1,
      events.off(app.createdEvent, this.initialize);
  };
  set visible(value: number) {
    this.projects.forEach((t) => {
      t.mesh.visible = value > 0.5;
    });
  }
  get visible() {
    return this.projects[0].mesh.visible ? 1 : 0;
  }
}

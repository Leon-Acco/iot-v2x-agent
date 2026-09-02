// 玻璃材质栈 —— 逐字移植 bundle：
//   ek（GlassBack，L52419-52458）  BackSide → backRT，采 Post.map
//   tk（GlassFront，L52820-52856） FrontSide → 主 rt，采 Post.backMap
//   gl（isGlassDispersion 判定，L52858-52860）
//   nk（装配，L52862-52925）       tangent 重写 / skinning guard / 双 pass render
// 怪癖照抄（REBUILD_PLAN §6）：
//   ① defines 初始小写 samplesCount，事件处理器写大写 SAMPLES_COUNT（死 define）
import {
  BackSide,
  BufferAttribute,
  Mesh,
  ShaderMaterial,
  SkinnedMesh,
  Vector3,
  type Texture,
} from "three";
import {
  Tick,
  appSettings,
  events,
  injectUniforms,
  stripAnnotations,
} from "../../core";
import { ShaderRegistry } from "../../shaderRegistry";
import { Performance } from "../../performance";
import {
  GLASS_BACK_DISPERSION_FRAG,
  GLASS_BACK_FRAG,
  GLASS_BACK_VERT,
  GLASS_FRONT_DISPERSION_FRAG,
  GLASS_FRONT_FRAG,
  GLASS_FRONT_VERT,
} from "./shaders";

// ek —— GlassBack（L52419-52458）。super 先吃色散版 b1，随后
// `this.fragmentShader = Tp(_1)` 覆盖成无色散版（构造怪癖照抄）。
export class GlassBackMaterial extends ShaderMaterial {
  constructor({
    hasSkinning = false,
    defaultDist = false,
    normalMap = null,
  }: { hasSkinning?: boolean; defaultDist?: boolean; normalMap?: Texture | null } = {}) {
    super(
      injectUniforms({
        defines: {
          samplesCount: appSettings.backSamplesCount.toString(),
          ...(hasSkinning && { USE_SKINNING: "" }),
          ...(defaultDist && { USE_DEFAULT_DIST: "" }),
          ...(normalMap && { USE_NORMAL_MAP: "" }),
        },
        uniforms: {
          normalMap: { value: normalMap },
        },
        vertexShader: GLASS_BACK_VERT,
        fragmentShader: GLASS_BACK_DISPERSION_FRAG,
        side: BackSide,
        depthTest: false,
        depthWrite: false,
      } as any),
    );
    this.fragmentShader = stripAnnotations(GLASS_BACK_FRAG);
    ShaderRegistry.add(this);
    if (appSettings.devMode) this.name = "GlassBack";
    events.on(Performance.hyperSamplingEvent, this.onHyperSamplingEvent);
    events.on(Performance.backDispersionEvent, this.onBackDispersionEvent);
  }
  onHyperSamplingEvent = (value: boolean) => {
    const count = value ? appSettings.hyperSamplesCount : appSettings.backSamplesCount;
    // 大写 SAMPLES_COUNT —— 死 define 照抄（§6 怪癖①）
    (this.defines as any).SAMPLES_COUNT = count.toString();
    this.needsUpdate = true;
  };
  onBackDispersionEvent = (value: boolean) => {
    const frag = value ? GLASS_BACK_DISPERSION_FRAG : GLASS_BACK_FRAG;
    ShaderRegistry.updateMaterial(
      this,
      stripAnnotations(GLASS_BACK_VERT),
      stripAnnotations(frag),
    );
  };
}

// tk —— GlassFront（L52820-52856）。fragmentShader 初始即色散版 x1，无覆盖。
export class GlassFrontMaterial extends ShaderMaterial {
  constructor({
    hasSkinning = false,
    normalMap = null,
  }: { hasSkinning?: boolean; normalMap?: Texture | null } = {}) {
    super(
      injectUniforms({
        defines: {
          samplesCount: appSettings.lowFrontSamplesCount.toString(),
          ...(hasSkinning && { USE_SKINNING: "" }),
          ...(normalMap && { USE_NORMAL_MAP: "" }),
        },
        uniforms: {
          normalMap: { value: normalMap },
        },
        vertexShader: GLASS_FRONT_VERT,
        fragmentShader: GLASS_FRONT_DISPERSION_FRAG,
      } as any),
    );
    if (appSettings.devMode) this.name = "GlassFront";
    ShaderRegistry.add(this);
    events.on(Performance.hyperSamplingEvent, this.onHyperSamplingEvent);
    events.on(Performance.highFrontSamplesEvent, this.onHighFrontSamplesEvent);
    events.on(Performance.frontDispersionEvent, this.onFrontDispersionEvent);
  }
  onHyperSamplingEvent = (value: boolean) => {
    const count = value ? appSettings.hyperSamplesCount : appSettings.frontSamplesCount;
    (this.defines as any).SAMPLES_COUNT = count.toString();
    this.needsUpdate = true;
  };
  onHighFrontSamplesEvent = (value: boolean) => {
    const count = value ? appSettings.frontSamplesCount : appSettings.lowFrontSamplesCount;
    (this.defines as any).SAMPLES_COUNT = count.toString();
    this.needsUpdate = true;
  };
  onFrontDispersionEvent = (value: boolean) => {
    const frag = value ? GLASS_FRONT_DISPERSION_FRAG : GLASS_FRONT_FRAG;
    ShaderRegistry.updateMaterial(
      this,
      stripAnnotations(GLASS_FRONT_VERT),
      stripAnnotations(frag),
    );
  };
}

// nk 挂到 Mesh 上的附加成员（L52900-52924）
export interface GlassDispersionMesh extends Mesh {
  isGlassDispersion: true;
  shouldBeSorted: boolean;
  frontMaterial: GlassFrontMaterial;
  backMaterial: GlassBackMaterial;
  geometryCenter: Vector3;
  geometryWorld: Vector3;
  closestToCenterVertexIndex: number;
  updateGeometryWorldPosition: () => void;
  render: (pipeline: any, camera: any, target: any) => void;
  disposeGlass: () => void;
}

// gl —— isGlassDispersion 判定（L52858-52860）
export function isGlassDispersion(obj: any): obj is GlassDispersionMesh {
  return obj && "isGlassDispersion" in obj && obj.isGlassDispersion === true;
}

// nk —— 玻璃装配（L52862-52925）：
// 1. tangent 属性若全 w===1 则重写为 itemSize 3（normalized 时不一致则 warn）
// 2. skinIndex+skinWeight+skeleton → hasSkinning；skeleton.update 按 Tick.id 去重
// 3. 无双精度 _dist 属性 → back 材质 USE_DEFAULT_DIST
// 4. render 双 pass：BackSide→backRT，FrontSide→主 target
export function setupGlassDispersion(mesh: Mesh, shouldBeSorted = true): GlassDispersionMesh {
  const geometry = mesh.geometry;
  const tangentAttr = geometry.getAttribute("tangent");
  if (appSettings.devMode) console.log("tangentAttr normalized: ", tangentAttr?.normalized);
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
        (array[i * 3 + 0] = tangentAttr.getX(i),
          (array[i * 3 + 1] = tangentAttr.getY(i)),
          (array[i * 3 + 2] = tangentAttr.getZ(i)));
      geometry.setAttribute(
        "tangent",
        new BufferAttribute(array, 3, tangentAttr.normalized === true),
      );
    } else if (tangentAttr.normalized) console.warn("not all w are ones");
  }
  const skinIndex = geometry.getAttribute("skinIndex");
  const skinWeight = geometry.getAttribute("skinWeight");
  const skeleton = (mesh as SkinnedMesh).skeleton || undefined;
  const hasSkinning = !!(skinIndex && skinWeight) && !!skeleton;
  if (skeleton) {
    const guard = skeleton as any;
    if (guard.__sweet3_updateGuardWrapped !== true) {
      guard.__sweet3_updateGuardWrapped = true;
      guard.__sweet3_lastUpdateTick = -1;
      const original = skeleton.update.bind(skeleton);
      skeleton.update = () => {
        if (guard.__sweet3_lastUpdateTick !== Tick.id) {
          guard.__sweet3_lastUpdateTick = Tick.id;
          original();
        }
      };
    }
  }
  const defaultDist = !geometry.hasAttribute("_dist");
  const frontMaterial = new GlassFrontMaterial({ hasSkinning });
  const backMaterial = new GlassBackMaterial({ hasSkinning, defaultDist });
  const glass = mesh as GlassDispersionMesh;
  glass.isGlassDispersion = true;
  glass.shouldBeSorted = shouldBeSorted;
  glass.frontMaterial = frontMaterial;
  glass.backMaterial = backMaterial;
  glass.geometry.computeBoundingBox();
  glass.geometryCenter = new Vector3();
  glass.geometry.boundingBox!.getCenter(glass.geometryCenter);
  glass.geometryWorld = new Vector3();
  glass.closestToCenterVertexIndex = -1;
  const position = geometry.getAttribute("position");
  if (hasSkinning && position?.count > 0) {
    let index = 0;
    let minDistance = Number.POSITIVE_INFINITY;
    const vertex = new Vector3();
    for (let i = 0; i < position.count; i++) {
      vertex.fromArray(position.array as ArrayLike<number>, i * 3);
      const distance = vertex.distanceToSquared(glass.geometryCenter);
      if (distance < minDistance) {
        minDistance = distance;
        index = i;
      }
    }
    glass.closestToCenterVertexIndex = index;
  }
  glass.updateGeometryWorldPosition = function (this: GlassDispersionMesh) {
    this.updateWorldMatrix(true, false);
    if (this.closestToCenterVertexIndex >= 0) {
      this.geometryWorld.copy(this.geometryCenter);
      (this as unknown as SkinnedMesh).applyBoneTransform(
        this.closestToCenterVertexIndex,
        this.geometryWorld,
      );
      this.geometryWorld.applyMatrix4(this.matrixWorld);
      return;
    }
    this.geometryWorld.copy(this.geometryCenter).applyMatrix4(this.matrixWorld);
  };
  glass.render = function (
    this: GlassDispersionMesh,
    pipeline: any,
    camera: any,
    target: any,
  ) {
    pipeline.renderer.setRenderTarget(pipeline.backRT);
    this.material = this.backMaterial;
    pipeline.renderer.render(this, camera);
    pipeline.renderer.setRenderTarget(target);
    this.material = this.frontMaterial;
    pipeline.renderer.render(this, camera);
  };
  glass.disposeGlass = function (this: GlassDispersionMesh) {
    this.frontMaterial.dispose();
    this.backMaterial.dispose();
    this.geometry.dispose();
  };
  glass.material = frontMaterial;
  return glass;
}

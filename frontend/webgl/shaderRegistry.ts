// ShaderRegistry (hn L50453-50493) + @sweet transformers in source order
// (L50465): gte (alpha, L50320-50325) → Ate (padding, L50418-50438) →
// vte (reflector, L50440-50451). Records original shader text per material;
// transformers rewrite macros; updateAll re-runs after a padding toggle
// (Performance.screenPaddingEvent), updateMaterial hot-swaps shader variants
// (dispersion on/off).
import { events, appSettings } from "./core";

export const SCREEN_PADDING_EVENT = "Performance.screenPaddingEvent";

interface MaterialLike {
  vertexShader: string;
  fragmentShader: string;
  needsUpdate: boolean;
  name?: string;
}
interface Record_ {
  material: MaterialLike;
  vertexShader: string;
  fragmentShader: string;
}

// gte — "@sweet alpha"（L50320-50325）：devMode 下替换成 1. 便于调试看底色。
// pattern 不含分号（源站如此，消费点写法各异）。transformers 顺序照抄
// hn（L50465）：[gte, Ate, vte]，alpha 必须排最前。
class AlphaTransformer {
  transform = (src: string) => {
    const t = appSettings.devMode ? "1." : "0.";
    return src.replaceAll("@sweet alpha", t);
  };
}

class PaddingTransformer {
  usePadding = true;
  constructor() {
    events.on(SCREEN_PADDING_EVENT, this.onScreenPaddingEvent);
  }
  onScreenPaddingEvent = (v: boolean) => {
    if (this.usePadding !== v) {
      this.usePadding = v;
      ShaderRegistry.update();
    }
  };
  transformVertex = (src: string) => {
    const repl = this.usePadding ? "gl_Position.xy /= 1.25;" : "";
    return src.replaceAll("@sweet padding;", repl);
  };
  transformFragment = (src: string) => {
    src = src.replace(/@sweet\s+padding-clip\s+(.+?)\s*;/g, (_m, expr) =>
      this.usePadding ? `${expr.trim()}.xy /= 1.25;` : "",
    );
    src = src.replace(/@sweet\s+padding-uv\s+(.+?)\s*;/g, (_m, expr) => {
      if (!this.usePadding) return "";
      const e = expr.trim();
      return `${e} = (${e} - 0.5) / 1.25 + 0.5;`;
    });
    src = src.replace(/@sweet\s+padding-unuv\s+(.+?)\s*;/g, (_m, expr) => {
      if (!this.usePadding) return "";
      const e = expr.trim();
      return `${e} = (${e} - 0.5) * 1.25 + 0.5;`;
    });
    return src;
  };
}

class ReflectorPaddingTransformer {
  usePadding = true;
  constructor() {
    events.on(SCREEN_PADDING_EVENT, this.onScreenPaddingEvent);
  }
  onScreenPaddingEvent = (v: boolean) => {
    if (this.usePadding !== v) {
      this.usePadding = v;
      ShaderRegistry.update();
    }
  };
  transformVertex = (src: string) => {
    const repl = this.usePadding
      ? "vCustomUV.xyz = ((vCustomUV.xyz / vCustomUV.w - 0.5) / 1.25 + 0.5) * vCustomUV.w;"
      : "";
    return src.replaceAll("@sweet reflector uv padding;", repl);
  };
}

export class ShaderRegistry {
  static instance = new ShaderRegistry();
  static add = (m: MaterialLike) => ShaderRegistry.instance.add(m);
  static updateMaterial = (m: MaterialLike, v: string, f: string) =>
    ShaderRegistry.instance.updateMaterial(m, v, f);
  static update = () => ShaderRegistry.instance.updateAll();
  records: Record_[] = [];
  transformers = [
    new AlphaTransformer(),
    new PaddingTransformer(),
    new ReflectorPaddingTransformer(),
  ];
  transform = (record: Record_) => {
    const { material, vertexShader, fragmentShader } = record;
    let v = vertexShader;
    let f = fragmentShader;
    for (const t of this.transformers as any[]) {
      if (t.transformVertex) v = t.transformVertex(v);
      if (t.transformFragment) f = t.transformFragment(f);
      if (t.transform) {
        v = t.transform(v);
        f = t.transform(f);
      }
    }
    material.vertexShader = v;
    material.fragmentShader = f;
    material.needsUpdate = true;
  };
  add = (material: MaterialLike) => {
    const record = {
      material,
      vertexShader: material.vertexShader,
      fragmentShader: material.fragmentShader,
    };
    this.records.push(record);
    this.transform(record);
  };
  updateMaterial = (material: MaterialLike, v: string, f: string) => {
    const record = this.records.find((r) => r.material === material);
    if (record) {
      record.vertexShader = v;
      record.fragmentShader = f;
      this.transform(record);
    } else throw new Error(`Record not found for material: ${material.name}!`);
  };
  updateAll = () => {
    for (const r of this.records) this.transform(r);
  };
}

// Asset manager — port of the Assets class (_pretty/CbdjwYMp.pretty.js
// L49675-49755) with the verbatim asset table (cZ L44144-44165).
import { LoadingManager, TextureLoader, HalfFloatType } from "three";
import { GLTFLoader } from "three/examples/jsm/loaders/GLTFLoader.js";
import { DRACOLoader } from "three/examples/jsm/loaders/DRACOLoader.js";
import { KTX2Loader } from "three/examples/jsm/loaders/KTX2Loader.js";
import { RGBELoader } from "three/examples/jsm/loaders/RGBELoader.js";
import { events } from "./core";

export class AssetEvents {
  static complete = "assets.complete";
  static progress = "assets.progress";
  static error = "assets.error";
  static assetRemoved = "assets.removed";
}

// cZ — L44144-44165, verbatim paths.
export const ASSET_TABLE: Record<string, string | string[]> = {
  blueNoise: "/textures/LDR_RG01_0.png",
  logo: "/textures/icon.png",
  crystals: new Array(7).fill(0).map((_n, i) => "/models/crystal" + i + ".glb"),
  crystalNormals: new Array(7).fill(0).map((_n, i) => "/textures/crystals/" + i + ".jpg"),
  env: "/textures/wooden_studio_19_1k.hdr",
};

class AssetEntry {
  name: string;
  url: string;
  type: string;
  asset: any = null;
  loaded = false;
  constructor(name: string, url: string, type: string) {
    this.name = name;
    this.url = url;
    this.type = type;
  }
}

export class Assets {
  assets: Record<string, AssetEntry> = {};
  loaders: Record<string, any> = {};
  dracoLoader: DRACOLoader | null = null;
  manager: LoadingManager;
  // KTX2 needs the renderer for transcoder support detection.
  getRenderer: () => any;
  constructor(getRenderer: () => any) {
    this.getRenderer = getRenderer;
    this.manager = new LoadingManager(
      () => events.dispatch(AssetEvents.complete, {}),
      (url, loaded, total) => events.dispatch(AssetEvents.progress, { url, loaded, total }),
      (url) => events.dispatch(AssetEvents.error, { url }),
    );
    Object.entries(ASSET_TABLE).forEach(([name, def]) => {
      this.addAsset(name, def);
    });
  }
  #createLoader = (type: string) => {
    switch (type) {
      case "jpg":
      case "png":
        return new TextureLoader(this.manager);
      case "ktx2": {
        const loader = new KTX2Loader(this.manager);
        loader.setTranscoderPath("/libs/basis/");
        loader.detectSupport(this.getRenderer());
        return loader;
      }
      case "gltf":
      case "glb": {
        const loader = new GLTFLoader(this.manager);
        if (this.dracoLoader === null) {
          this.dracoLoader = new DRACOLoader(this.manager);
          this.dracoLoader.setDecoderPath("/libs/draco/");
        }
        loader.setDRACOLoader(this.dracoLoader);
        return loader;
      }
      case "hdr":
        return new RGBELoader(this.manager).setDataType(HalfFloatType);
      default:
        throw `Unknown resource type ${type}!`;
    }
  };
  #loader = (type: string) =>
    type in this.loaders ? this.loaders[type] : (this.loaders[type] = this.#createLoader(type));
  addAsset = (name: string, def: string | string[] | { url: string; type: string }) => {
    if (Array.isArray(def)) {
      name = name.slice(0, -1);
      def.forEach((item, i) => {
        this.addAsset(name + i, item);
      });
      return;
    }
    if (this.has(name)) {
      console.warn(`${name} already exists in assets!`);
      this.remove(name);
    }
    let type: string;
    let url: string;
    if (typeof def === "string") {
      url = def;
      type = url.substring(url.lastIndexOf(".") + 1, url.length);
    } else {
      url = def.url;
      type = def.type;
    }
    this.assets[name] = new AssetEntry(name, url, type);
  };
  loadAll = () => {
    const pending = Object.values(this.assets).filter((a) => !a.loaded);
    if (pending.length === 0) {
      events.dispatch(AssetEvents.complete, {});
      return Promise.resolve([]);
    }
    return Promise.all(pending.map((a) => this.loadFile(a.name)));
  };
  loadFile = (name: string) => {
    if (!this.has(name)) throw `${name} is not found in assets! Aborting loading`;
    if (this.assets[name].loaded) return Promise.resolve(this.assets[name].asset);
    return this.#loader(this.assets[name].type)
      .loadAsync(this.assets[name].url)
      .then((asset: any) => {
        this.assets[name].asset = asset;
        this.assets[name].loaded = true;
        return asset;
      });
  };
  has = (name: string) => name in this.assets;
  get = (name: string) => {
    if (!(name in this.assets)) throw `${name} is not found in assets!`;
    return this.assets[name].asset;
  };
  remove = (name: string) => {
    if (!(name in this.assets)) return false;
    delete this.assets[name];
    events.dispatch(AssetEvents.assetRemoved, { name });
    return true;
  };
}

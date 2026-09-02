// colorsMap / blueNoise —— 逐字移植 xE（L55885-55892）与 yE（L55877-55883）。
// xE：colorsMap 内联 base64（bie L55884），data URL 加载，LinearFilter+sRGB。
// yE：blueNoise 走 assetsManager（/textures/LDR_RG01_0.png），min/mag LinearFilter。
import { LinearFilter, SRGBColorSpace, TextureLoader } from "three";
import { app, setProvider } from "../../core";
import { COLORS_MAP_BASE64 } from "./colorsMapBase64";

// yE —— blueNoise provider（L55877-55883）
export class BlueNoiseMap {
  static blueNoiseMapProvider = "BlueNoise.map";
  constructor() {
    const texture = app.assetsManager.get("blueNoise");
    texture.minFilter = LinearFilter;
    texture.magFilter = LinearFilter;
    setProvider(BlueNoiseMap.blueNoiseMapProvider, texture);
  }
}

// xE —— colorsMap provider（L55885-55892）
export class ColorsMap {
  static colorsMapProvider = "ColorsMap.map";
  constructor() {
    const url = "data:image/png;base64," + COLORS_MAP_BASE64;
    const texture = new TextureLoader().load(url);
    texture.minFilter = LinearFilter;
    texture.magFilter = LinearFilter;
    texture.colorSpace = SRGBColorSpace;
    setProvider(ColorsMap.colorsMapProvider, texture);
  }
}

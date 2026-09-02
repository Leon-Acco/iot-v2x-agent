// 玻璃参数表 —— 逐字搬运自 bundle 设置块（_pretty/CbdjwYMp.pretty.js L55042-55443）。
// glassConfig 23 条（驱动 lne 弹簧链）、crystal 默认值、crystalHovers 7 套 hover 参数。
// 怪癖照抄：crystalHovers[2] 无 resetDistances 键（L55315-55338，已登记 REBUILD_PLAN §6）。
import { Color } from "three";

// 采样档位（L55045-55048）
export const HYPER_SAMPLES_COUNT = 16;
export const BACK_SAMPLES_COUNT = 5;
export const FRONT_SAMPLES_COUNT = 12;
export const LOW_FRONT_SAMPLES_COUNT = 5;

// 凤凰动画映射（L55042-55043）：硬切，不混合
export const GLASS_ANIMATION_ID_TO_CLIP_NAME = [
  "Idle_MainPose_flying",
  "Float_WingPulse",
  "Wing_CloseUp",
  "Idle_MainPose_gliding",
];
export const GLASS_ANIMATION_ID_TO_SPEED = [1, 1, 1, 1];

export interface GlassParamConfig {
  min: number;
  max: number;
  value: number;
  default?: number;
  randomize: boolean;
  minRandom?: number;
  maxRandom?: number;
  timelinePath: string;
}

// glassConfig（L55049-55234，23 条）
export const GLASS_CONFIG: Record<string, GlassParamConfig> = {
  "Glass.resetDistances": {
    min: 0,
    max: 1,
    value: 0,
    randomize: false,
    timelinePath: "Glass_distResetX.position.x",
  },
  "Glass.distancesFactor": {
    min: 0,
    max: 50,
    value: 1,
    randomize: false,
    timelinePath: "Glass_distResetX.position.y",
  },
  "Glass.iorStart": {
    min: 1,
    max: 2,
    value: 1,
    default: 1.2,
    randomize: true,
    timelinePath: "Glass_iorVDeltaXshift.position.x",
  },
  "Glass.iorDelta": {
    min: 0,
    max: 5,
    value: 0,
    default: 0.3,
    randomize: true,
    timelinePath: "Glass_iorVDeltaXshift.position.y",
  },
  "Glass.uvShiftFactor": {
    min: 1,
    max: 50,
    value: 1,
    randomize: true,
    minRandom: 1,
    maxRandom: 5,
    timelinePath: "Glass_iorVDeltaXshift.position.z",
  },
  "Glass.envReflection": {
    min: 0,
    max: 1,
    value: 0,
    default: 1,
    randomize: true,
    timelinePath: "Glass_reflectionVIri.position.x",
  },
  "Glass.envRefraction": {
    min: 0,
    max: 1,
    value: 0,
    randomize: true,
    timelinePath: "Glass_refractionVIri.position.x",
  },
  "Glass.reflectionIridescence": {
    min: 0,
    max: 1,
    value: 0,
    randomize: true,
    timelinePath: "Glass_reflectionVIri.position.y",
  },
  "Glass.refractionIridescence": {
    min: 0,
    max: 1,
    value: 0,
    randomize: true,
    timelinePath: "Glass_refractionVIri.position.y",
  },
  "Glass.convexityFactor": {
    min: 0,
    max: 1,
    value: 0,
    default: 1,
    randomize: true,
    timelinePath: "Glass_convexConcavePeaks.position.x",
  },
  "Glass.concavityFactor": {
    min: 0,
    max: 1,
    value: 0,
    default: 1,
    randomize: true,
    timelinePath: "Glass_convexConcavePeaks.position.y",
  },
  "Glass.peaksFactor": {
    min: 0,
    max: 3,
    value: 1,
    default: 1,
    randomize: true,
    timelinePath: "Glass_convexConcavePeaks.position.z",
  },
  "Glass.fringeCurve": {
    min: 0,
    max: 5,
    value: 5,
    randomize: true,
    timelinePath: "Glass_fringeCurveMix.position.x",
  },
  "Glass.fringeMix": {
    min: 0,
    max: 1,
    value: 0,
    default: 1,
    randomize: true,
    timelinePath: "Glass_fringeCurveMix.position.y",
  },
  "Glass.colorBoost": {
    min: 0,
    max: 3,
    value: 1,
    randomize: true,
    minRandom: 1,
    maxRandom: 2,
    timelinePath: "Glass_colorBoostFactorCurve.position.x",
  },
  "Glass.colorFactor": {
    min: 0,
    max: 3,
    value: 1,
    randomize: true,
    minRandom: 0.5,
    maxRandom: 1.5,
    timelinePath: "Glass_colorBoostFactorCurve.position.y",
  },
  "Glass.colorCurve": {
    min: 0.001,
    max: 2,
    value: 1,
    randomize: true,
    minRandom: 0.9,
    maxRandom: 1.111,
    timelinePath: "Glass_colorBoostFactorCurve.position.z",
  },
  "Glass.colorCurveR": {
    min: 0.001,
    max: 2,
    value: 1,
    randomize: true,
    minRandom: 0.9,
    maxRandom: 1.111,
    timelinePath: "Glass_colorCurveRGB.position.x",
  },
  "Glass.colorCurveG": {
    min: 0.001,
    max: 2,
    value: 1,
    randomize: true,
    minRandom: 0.9,
    maxRandom: 1.111,
    timelinePath: "Glass_colorCurveRGB.position.y",
  },
  "Glass.colorCurveB": {
    min: 0.001,
    max: 2,
    value: 1,
    randomize: true,
    minRandom: 0.9,
    maxRandom: 1.111,
    timelinePath: "Glass_colorCurveRGB.position.z",
  },
  "Glass.maxColorValue": {
    min: 1,
    max: 100,
    value: 25,
    randomize: true,
    timelinePath: "Glass_colorMaxvalDecayUsetransmittance.position.x",
  },
  "Glass.decayFactor": {
    min: 0,
    max: 1e4,
    value: 0,
    default: 20,
    randomize: true,
    timelinePath: "Glass_colorMaxvalDecayUsetransmittance.position.y",
  },
  "Glass.useTransmittance": {
    min: 0,
    max: 1,
    value: 0,
    default: 1,
    randomize: false,
    timelinePath: "Glass_colorMaxvalDecayUsetransmittance.position.z",
  },
};

export interface CrystalParams {
  baseColor: Color;
  peaksColor: Color;
  fringeColor: Color;
  resetDistances?: number;
  distancesFactor: number;
  iorStart: number;
  iorDelta: number;
  uvShiftFactor: number;
  envReflection: number;
  envRefraction: number;
  reflectionIridescence: number;
  refractionIridescence: number;
  convexityFactor: number;
  concavityFactor: number;
  peaksFactor: number;
  fringeCurve: number;
  fringeMix: number;
  colorBoost: number;
  colorFactor: number;
  colorCurve: number;
  colorCurveR: number;
  colorCurveG: number;
  colorCurveB: number;
  maxColorValue: number;
  decayFactor: number;
}

// crystal 默认值（L55235-55261；无 useTransmittance 键）
export const CRYSTAL_DEFAULTS: CrystalParams = {
  baseColor: new Color(16777215),
  peaksColor: new Color(16777215),
  fringeColor: new Color(16777215),
  resetDistances: 0,
  distancesFactor: 1,
  iorStart: 1.2,
  iorDelta: 0.3,
  uvShiftFactor: 1,
  envReflection: 1,
  envRefraction: 0,
  reflectionIridescence: 0,
  refractionIridescence: 0,
  convexityFactor: 1,
  concavityFactor: 1,
  peaksFactor: 1,
  fringeCurve: 5,
  fringeMix: 1,
  colorBoost: 2,
  colorFactor: 2,
  colorCurve: 1.5,
  colorCurveR: 1,
  colorCurveG: 1,
  colorCurveB: 1,
  maxColorValue: 25,
  decayFactor: 20,
};

// crystalHovers（L55262-55443，7 套；[2] 无 resetDistances 键，照抄）
export const CRYSTAL_HOVERS: CrystalParams[] = [
  {
    baseColor: new Color(16508551),
    peaksColor: new Color(16313566),
    fringeColor: new Color(16183003),
    resetDistances: 0.33,
    distancesFactor: 22.22,
    iorStart: 1.3,
    iorDelta: 0.33,
    uvShiftFactor: 1.8,
    envReflection: 0.22,
    envRefraction: 0.72,
    reflectionIridescence: 0.16,
    refractionIridescence: 0.95,
    convexityFactor: 0.72,
    concavityFactor: 0.52,
    peaksFactor: 0.84,
    fringeCurve: 3.23,
    fringeMix: 0.83,
    colorBoost: 0.04,
    colorFactor: 2.58,
    colorCurve: 1.37,
    colorCurveR: 1,
    colorCurveG: 1.11,
    colorCurveB: 1.11,
    maxColorValue: 50,
    decayFactor: 250,
  },
  {
    baseColor: new Color(8891643),
    peaksColor: new Color(16313566),
    fringeColor: new Color(16183003),
    resetDistances: 0.33,
    distancesFactor: 22,
    iorStart: 1.3,
    iorDelta: 0.33,
    uvShiftFactor: 1.8,
    envReflection: 0.22,
    envRefraction: 0.72,
    reflectionIridescence: 0.15,
    refractionIridescence: 0.95,
    convexityFactor: 0.72,
    concavityFactor: 0.52,
    peaksFactor: 0.84,
    fringeCurve: 3.23,
    fringeMix: 0.83,
    colorBoost: 0.04,
    colorFactor: 2.58,
    colorCurve: 1.37,
    colorCurveR: 1,
    colorCurveG: 1.11,
    colorCurveB: 1.11,
    maxColorValue: 50,
    decayFactor: 250,
  },
  {
    baseColor: new Color(12778185),
    peaksColor: new Color(16708857),
    fringeColor: new Color(13367271),
    distancesFactor: 15,
    iorStart: 1.33,
    iorDelta: 3,
    uvShiftFactor: 3,
    envReflection: 1,
    envRefraction: 0.88,
    reflectionIridescence: 0.47,
    refractionIridescence: 0.8,
    convexityFactor: 1,
    concavityFactor: 0.55,
    peaksFactor: 0.58,
    fringeCurve: 2.5,
    fringeMix: 0.7,
    colorBoost: 0.27,
    colorFactor: 2.5,
    colorCurve: 1.8,
    colorCurveR: 1.2,
    colorCurveG: 0.99,
    colorCurveB: 0.92,
    maxColorValue: 65,
    decayFactor: 500,
  },
  {
    baseColor: new Color(16756986),
    peaksColor: new Color(15196927),
    fringeColor: new Color(14082556),
    resetDistances: 0,
    distancesFactor: 6,
    iorStart: 1.65,
    iorDelta: 5,
    uvShiftFactor: 2.14,
    envReflection: 0.8,
    envRefraction: 0.75,
    reflectionIridescence: 0.85,
    refractionIridescence: 0.75,
    convexityFactor: 0.22,
    concavityFactor: 0.67,
    peaksFactor: 2.67,
    fringeCurve: 4.8,
    fringeMix: 0.63,
    colorBoost: 0.5,
    colorFactor: 2,
    colorCurve: 0.72,
    colorCurveR: 1.14,
    colorCurveG: 1.11,
    colorCurveB: 1.16,
    maxColorValue: 100,
    decayFactor: 500,
  },
  {
    baseColor: new Color(13360601),
    peaksColor: new Color(16371685),
    fringeColor: new Color(13489115),
    resetDistances: 0,
    distancesFactor: 4.35,
    iorStart: 1.98,
    iorDelta: 4,
    uvShiftFactor: 5,
    envReflection: 0.52,
    envRefraction: 0.49,
    reflectionIridescence: 0.5,
    refractionIridescence: 0.17,
    convexityFactor: 0.01,
    concavityFactor: 0.7,
    peaksFactor: 1.2,
    fringeCurve: 2.22,
    fringeMix: 0.65,
    colorBoost: 1.33,
    colorFactor: 2,
    colorCurve: 0.45,
    colorCurveR: 1.07,
    colorCurveG: 1.37,
    colorCurveB: 0.94,
    maxColorValue: 35,
    decayFactor: 50,
  },
  {
    baseColor: new Color(5168111),
    peaksColor: new Color(12763391),
    fringeColor: new Color(16579836),
    resetDistances: 0,
    distancesFactor: 6,
    iorStart: 1.22,
    iorDelta: 5,
    uvShiftFactor: 1,
    envReflection: 0.68,
    envRefraction: 0.34,
    reflectionIridescence: 0.38,
    refractionIridescence: 0.22,
    convexityFactor: 0.21,
    concavityFactor: 0.55,
    peaksFactor: 2.34,
    fringeCurve: 3.02,
    fringeMix: 0.68,
    colorBoost: 0.3,
    colorFactor: 2,
    colorCurve: 1.24,
    colorCurveR: 0.28,
    colorCurveG: 1.52,
    colorCurveB: 1.04,
    maxColorValue: 100,
    decayFactor: 350,
  },
  {
    baseColor: new Color(13562338),
    peaksColor: new Color(14998271),
    fringeColor: new Color(14086114),
    resetDistances: 0,
    distancesFactor: 24,
    iorStart: 1.5,
    iorDelta: 0.5,
    uvShiftFactor: 2.86,
    envReflection: 0.21,
    envRefraction: 0.95,
    reflectionIridescence: 0.9,
    refractionIridescence: 0.88,
    convexityFactor: 0.73,
    concavityFactor: 0.56,
    peaksFactor: 1.77,
    fringeCurve: 4.51,
    fringeMix: 0.71,
    colorBoost: 0.14,
    colorFactor: 2.58,
    colorCurve: 1.16,
    colorCurveR: 1.05,
    colorCurveG: 1.09,
    colorCurveB: 0.93,
    maxColorValue: 100,
    decayFactor: 450,
  },
];

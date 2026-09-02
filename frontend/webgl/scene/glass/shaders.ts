// 玻璃栈 6 段 GLSL —— 逐字搬运自 bundle（_pretty/CbdjwYMp.pretty.js），
// 由 scripts/verify-glass-glsl.mjs 机器门禁兜底（按行号切片逐字节 diff）。
// 命名对应：GlassBack 无色散 frag = _1（L52128-52235）、色散 frag = b1（L52236-52371）、
// vert = y1（L52372-52418）；GlassFront 无色散 frag = qte（L52459-52587）、
// 色散 frag = x1（L52588-52778）、vert = w1（L52779-52819）。
// 怪癖照抄：行尾空格（- 1.;␣）、x1 的纯空格空行、y1/qte/x1 硬编码 /= 1.25（不走宏）。

// _1 — GlassBack 默认 fragment（无色散）
export const GLASS_BACK_FRAG = `#define pi 3.14159265358979323846
#define saturate(x) clamp(x, 0., 1.)

varying vec3 vPosition;
varying vec3 vNormal;
varying vec3 vTangent;
varying float vDist;
varying float vCurvature;
varying vec2 vUv;

uniform mat4 modelMatrix;
uniform mat4 projectionMatrix;
uniform sampler2D map@Post.map;
uniform float envRefraction@Glass.envRefraction;
uniform float iorStart@Glass.iorStart;
uniform float iorDelta@Glass.iorDelta;
uniform float refractionIridescence@Glass.refractionIridescence;
uniform float fringeCurve@Glass.fringeCurve;
uniform float fringeMix@Glass.fringeMix;
uniform vec3 fringeColor@Glass.fringeColor;
uniform float useTransmittance@Glass.useTransmittance;

uniform sampler2D envMap@Env.map;

#define oneOverPi 0.3183098861837907
vec3 getEnvColor(vec3 ray) {
  vec2 uv = vec2(atan(ray.x, ray.z) * 0.5, asin(ray.y));
  uv = uv * oneOverPi + 0.5;
  uv.x = fract(uv.x);
  vec3 color = texture2D(envMap, uv).rgb;
  color = 1. - exp(-0.1 * color);
  return color;
}
uniform sampler2D colorsMap@ColorsMap.map;

vec3 boostSaturation(vec3 rgb, float boost) {
  float maxC = max(rgb.r, max(rgb.g, rgb.b));
  float minC = min(rgb.r, min(rgb.g, rgb.b));
  float S = (maxC > 1e-6) ? ((maxC - minC) / maxC) : 0.;
  float Sp = clamp(S * boost, 0., 1.);
  float ratio = (S > 1e-6) ? (Sp / S) : 0.;
  return clamp(maxC - (maxC - rgb) * ratio, 0., 1.);
}

vec3 mixToColor(float f) {
  return texture2D(colorsMap, vec2(f, 0.)).rgb;
}

vec3 getIridescence(vec3 rd, vec3 n) {
  float thickness = 1. - abs(dot(n, rd));
  return texture2D(colorsMap, vec2(thickness * 0.3 + 0.08, 1.)).rgb;
}
#ifdef USE_NORMAL_MAP
  uniform sampler2D normalMap;
#endif

vec3 getNormal() {
  vec3 N = normalize(vNormal);
  #ifdef USE_NORMAL_MAP
    vec2 uv = vUv;
    vec3 mapN = normalize(texture2D(normalMap, uv).xyz * 2.0 - 1.0);
    vec3 T = normalize(vTangent);
    vec3 B = normalize(cross(N, T));
    mat3 tbn = mat3(T, B, N);
    vec3 newN = normalize(tbn * mapN);
    return normalize(mat3(modelMatrix) * newN);
  #else
    return normalize(mat3(modelMatrix) * N);
  #endif
}

float fresnelSchlick(vec3 ray, vec3 normal) {
  float cosTheta = abs(dot(normalize(ray), normal));
  float r0 = 0.04;
  return r0 + (1. - r0) * pow(1. - cosTheta, 5.);
}

void main() {
  vec3 normal = getNormal();
  vec3 viewDirection = normalize(vPosition - cameraPosition);
  float dotNormalView = dot(normal, viewDirection);

  vec3 refraction = refract(viewDirection, normal, 1. / iorStart);

  float transmittance = 1. - useTransmittance * fresnelSchlick(refraction, normal);

  vec4 clip = projectionMatrix * viewMatrix * vec4(vPosition + refraction * vDist, 1.0);
  @sweet padding-clip clip;
  vec2 uv = saturate(clip.xy / clip.w * 0.5 + 0.5);

  vec3 color = vec3(0.);
  float fringeness = pow(saturate(1. - abs(dotNormalView)), fringeCurve) * fringeMix;

  vec3 texel = texture2D(map, uv).rgb;

  vec3 ray = normalize(refraction);
  if (envRefraction > 0.) {
    texel += getEnvColor(ray) * envRefraction;
  }

  texel = mix(texel, fringeColor, fringeness);
  color += texel * transmittance * (1. + vCurvature * abs(dot(ray, normal)));

  vec3 iridescence = getIridescence(refraction, normal) - 1.; 
  color *= refractionIridescence * iridescence + 1.;

  gl_FragColor = vec4(color, @sweet alpha);
}`;

// b1 — GlassBack 色散 fragment（backDispersion 开启时由 hn.updateMaterial 热替换）
export const GLASS_BACK_DISPERSION_FRAG = `#define pi 3.14159265358979323846
#define saturate(x) clamp(x, 0., 1.)

varying vec3 vPosition;
varying vec3 vNormal;
varying vec3 vTangent;
varying float vDist;
varying float vCurvature;
varying vec2 vUv;

uniform mat4 modelMatrix;
uniform mat4 projectionMatrix;
uniform sampler2D map@Post.map;
uniform sampler2D noiseMap@BlueNoise.map;
uniform float envRefraction@Glass.envRefraction;
uniform float iorStart@Glass.iorStart;
uniform float iorDelta@Glass.iorDelta;
uniform float refractionIridescence@Glass.refractionIridescence;
uniform float uvShiftFactor@Glass.uvShiftFactor;
uniform float fringeCurve@Glass.fringeCurve;
uniform float fringeMix@Glass.fringeMix;
uniform vec3 fringeColor@Glass.fringeColor;
uniform float seconds@Tick.seconds;
uniform float useTransmittance@Glass.useTransmittance;

uniform sampler2D envMap@Env.map;

#define oneOverPi 0.3183098861837907
vec3 getEnvColor(vec3 ray) {
  vec2 uv = vec2(atan(ray.x, ray.z) * 0.5, asin(ray.y));
  uv = uv * oneOverPi + 0.5;
  uv.x = fract(uv.x);
  vec3 color = texture2D(envMap, uv).rgb;
  color = 1. - exp(-0.1 * color);
  return color;
}
uniform sampler2D colorsMap@ColorsMap.map;

vec3 boostSaturation(vec3 rgb, float boost) {
  float maxC = max(rgb.r, max(rgb.g, rgb.b));
  float minC = min(rgb.r, min(rgb.g, rgb.b));
  float S = (maxC > 1e-6) ? ((maxC - minC) / maxC) : 0.;
  float Sp = clamp(S * boost, 0., 1.);
  float ratio = (S > 1e-6) ? (Sp / S) : 0.;
  return clamp(maxC - (maxC - rgb) * ratio, 0., 1.);
}

vec3 mixToColor(float f) {
  return texture2D(colorsMap, vec2(f, 0.)).rgb;
}

vec3 getIridescence(vec3 rd, vec3 n) {
  float thickness = 1. - abs(dot(n, rd));
  return texture2D(colorsMap, vec2(thickness * 0.3 + 0.08, 1.)).rgb;
}
#ifdef USE_NORMAL_MAP
  uniform sampler2D normalMap;
#endif

vec3 getNormal() {
  vec3 N = normalize(vNormal);
  #ifdef USE_NORMAL_MAP
    vec2 uv = vUv;
    vec3 mapN = normalize(texture2D(normalMap, uv).xyz * 2.0 - 1.0);
    vec3 T = normalize(vTangent);
    vec3 B = normalize(cross(N, T));
    mat3 tbn = mat3(T, B, N);
    vec3 newN = normalize(tbn * mapN);
    return normalize(mat3(modelMatrix) * newN);
  #else
    return normalize(mat3(modelMatrix) * N);
  #endif
}

float fresnelSchlick(vec3 ray, vec3 normal) {
  float cosTheta = abs(dot(normalize(ray), normal));
  float r0 = 0.04;
  return r0 + (1. - r0) * pow(1. - cosTheta, 5.);
}

void main() {
  vec3 normal = getNormal();
  vec3 viewDirection = normalize(vPosition - cameraPosition);
  float dotNormalView = dot(normal, viewDirection);

  vec3 refractionA = refract(viewDirection, normal, 1. / iorStart);
  vec3 refractionB = refract(viewDirection, normal, 1. / (iorStart + iorDelta));

  float transmittance = 1. - useTransmittance * fresnelSchlick(refractionA, normal);

  vec4 clipA = projectionMatrix * viewMatrix * vec4(vPosition + refractionA * vDist, 1.0);
  @sweet padding-clip clipA;
  vec4 clipB = projectionMatrix * viewMatrix * vec4(vPosition + refractionB * vDist, 1.0);
  @sweet padding-clip clipB;

  vec2 uvA = saturate(clipA.xy / clipA.w * 0.5 + 0.5);
  vec2 uvB = saturate(clipB.xy / clipB.w * 0.5 + 0.5);
  vec2 dUv = (uvB - uvA) * uvShiftFactor;
  vec2 noiseUv = fract(uvA * 777. + seconds);

  vec3 color = vec3(0.0);
  vec3 palAccum = vec3(0.0);

  float dq = 1. / float(samplesCount);
  float blue = texture2D(noiseMap, noiseUv).r;
  float mixFactor = blue * dq;
  vec2 uv;
  vec3 ray, texel, pal;
  float fringeness = pow(saturate(1. - abs(dotNormalView)), fringeCurve) * fringeMix;

  #pragma unroll_loop_start
  for (int i = 0; i < samplesCount; i++) {
    uv = uvA + dUv * mixFactor;
    texel = texture2D(map, uv).rgb;

    ray = normalize(mix(refractionA, refractionB, mixFactor));
    if (envRefraction > 0.) {
      texel += getEnvColor(ray) * envRefraction;
    }

    pal = mixToColor(mixFactor);
    palAccum += pal;

    texel = mix(texel, fringeColor, fringeness);
    color += texel * pal * transmittance * (1. + vCurvature * abs(dot(ray, normal)));
    mixFactor += dq;
  }
  #pragma unroll_loop_end

  color /= palAccum;

  vec3 iridescence = getIridescence(refractionA, normal) - 1.; 
  color *= refractionIridescence * iridescence + 1.;

  gl_FragColor = vec4(color, @sweet alpha);
}`;

// y1 — GlassBack vertex（怪癖②：硬编码 gl_Position.xy /= 1.25，不走 @sweet padding 宏）
export const GLASS_BACK_VERT = `attribute float _dist;
attribute float _convexity;
attribute float _concavity;
attribute vec3 tangent;

#include <skinning_pars_vertex>

varying float vDist;
varying float vCurvature;
varying vec3 vPosition;
varying vec3 vNormal;
varying vec3 vTangent;
varying vec2 vUv;

uniform float convexityFactor@Glass.convexityFactor;
uniform float concavityFactor@Glass.concavityFactor;
uniform float distancesFactor@Glass.distancesFactor;
uniform float resetDistances@Glass.resetDistances;

#define defaultDist 2.

void main() {
  float dist = _dist;
#ifdef USE_DEFAULT_DIST
  dist = defaultDist;
#endif
  vDist = mix(dist * distancesFactor, 0.1, resetDistances);
  vCurvature = _convexity * convexityFactor - _concavity * concavityFactor;
  vUv = uv;

  vec3 transformed = position;
  vec3 objectNormal = normal;
  vec3 objectTangent = tangent;

  #include <skinbase_vertex>
  #include <skinnormal_vertex>
  #include <skinning_vertex>

  vNormal = -objectNormal;
  vTangent = objectTangent;

  vec4 worldPosition = modelMatrix * vec4(transformed, 1.0);
  vPosition = worldPosition.xyz;

  gl_Position = projectionMatrix * viewMatrix * worldPosition;
  gl_Position.xy /= 1.25;
}`;

// qte — GlassFront 默认 fragment（无色散；硬编码 clip.xy /= 1.25）
export const GLASS_FRONT_FRAG = `#define pi 3.14159265358979323846
#define saturate(x) clamp(x, 0., 1.)

varying vec3 vNormal;
varying vec3 vTangent;
varying vec3 vPosition;
varying float vThickness;
varying vec2 vUv;
varying vec3 vGlassColor;

uniform mat4 modelMatrix;
uniform mat4 projectionMatrix;
uniform float envReflection@Glass.envReflection;
uniform sampler2D map@Post.backMap;
uniform float colorFactor@Glass.colorFactor;
uniform float iorStart@Glass.iorStart;
uniform float iorDelta@Glass.iorDelta;
uniform float reflectionIridescence@Glass.reflectionIridescence;
uniform float colorBoost@Glass.colorBoost;
uniform float decayFactor@Glass.decayFactor;
uniform float maxColorValue@Glass.maxColorValue;
uniform float useTransmittance@Glass.useTransmittance;
uniform float fringeCurve@Glass.fringeCurve;
uniform float fringeMix@Glass.fringeMix;
uniform vec3 fringeColor@Glass.fringeColor;
uniform float colorCurve@Glass.colorCurve;
uniform float colorCurveR@Glass.colorCurveR;
uniform float colorCurveG@Glass.colorCurveG;
uniform float colorCurveB@Glass.colorCurveB;

uniform sampler2D envMap@Env.map;

#define oneOverPi 0.3183098861837907
vec3 getEnvColor(vec3 ray) {
  vec2 uv = vec2(atan(ray.x, ray.z) * 0.5, asin(ray.y));
  uv = uv * oneOverPi + 0.5;
  uv.x = fract(uv.x);
  vec3 color = texture2D(envMap, uv).rgb;
  color = 1. - exp(-0.1 * color);
  return color;
}
uniform sampler2D colorsMap@ColorsMap.map;

vec3 boostSaturation(vec3 rgb, float boost) {
  float maxC = max(rgb.r, max(rgb.g, rgb.b));
  float minC = min(rgb.r, min(rgb.g, rgb.b));
  float S = (maxC > 1e-6) ? ((maxC - minC) / maxC) : 0.;
  float Sp = clamp(S * boost, 0., 1.);
  float ratio = (S > 1e-6) ? (Sp / S) : 0.;
  return clamp(maxC - (maxC - rgb) * ratio, 0., 1.);
}

vec3 mixToColor(float f) {
  return texture2D(colorsMap, vec2(f, 0.)).rgb;
}

vec3 getIridescence(vec3 rd, vec3 n) {
  float thickness = 1. - abs(dot(n, rd));
  return texture2D(colorsMap, vec2(thickness * 0.3 + 0.08, 1.)).rgb;
}
#ifdef USE_NORMAL_MAP
  uniform sampler2D normalMap;
#endif

vec3 getNormal() {
  vec3 N = normalize(vNormal);
  #ifdef USE_NORMAL_MAP
    vec2 uv = vUv;
    vec3 mapN = normalize(texture2D(normalMap, uv).xyz * 2.0 - 1.0);
    vec3 T = normalize(vTangent);
    vec3 B = normalize(cross(N, T));
    mat3 tbn = mat3(T, B, N);
    vec3 newN = normalize(tbn * mapN);
    return normalize(mat3(modelMatrix) * newN);
  #else
    return normalize(mat3(modelMatrix) * N);
  #endif
}

float fresnelSchlick(vec3 ray, vec3 normal) {
  float cosTheta = abs(dot(ray, normal));
  return 0.04 + 0.96 * pow(1. - cosTheta, 5.);
}

void main() {
  vec3 normal = getNormal();
  vec3 viewDirection = normalize(vPosition - cameraPosition);

  vec3 refraction = refract(viewDirection, normal, 1. / iorStart);
  vec4 clip = projectionMatrix * viewMatrix * vec4(vPosition + refraction * vThickness, 1.0);
  clip.xy /= 1.25;
  vec2 uv = saturate(clip.xy / clip.w * 0.5 + 0.5);

  float transmittance = 1. - useTransmittance * fresnelSchlick(refraction, normal);

  vec3 color = texture2D(map, uv).rgb * transmittance;

  float fringeness = fringeMix * pow(saturate(1. - abs(dot(viewDirection, normal))), fringeCurve);
  color = mix(color, fringeColor, fringeness);

  float luminance = dot(color, vec3(0.2126, 0.7152, 0.0722));
  color = (color - luminance) * colorBoost + luminance;
  color = max(color, 0.);

  float decay = exp(-vThickness * decayFactor);
  color *= mix(vGlassColor, vec3(1.), decay);

  vec3 iridescence = getIridescence(viewDirection, normal) - 1.; 
  iridescence = reflectionIridescence * iridescence + 1.;

  color *= colorFactor;
  if (color.r < 1.) {
    color.r = pow(color.r, colorCurve * colorCurveR);
  }
  if (color.g < 1.) {
    color.g = pow(color.g, colorCurve * colorCurveG);
  }
  if (color.b < 1.) {
    color.b = pow(color.b, colorCurve * colorCurveB);
  }

  float fresnel = fresnelSchlick(viewDirection, normal);
  vec3 ray = reflect(viewDirection, normal);
  color += getEnvColor(ray) * envReflection * fresnel * iridescence;

  color = clamp(color, 0., maxColorValue);

  gl_FragColor = vec4(color, @sweet alpha);
}`;

// x1 — GlassFront 色散 fragment（frontDispersion 开启时热替换；保留 L52701/52738-52746 空白怪癖）
export const GLASS_FRONT_DISPERSION_FRAG = `#define pi 3.14159265358979323846
#define saturate(x) clamp(x, 0., 1.)

varying vec3 vNormal;
varying vec3 vTangent;
varying vec3 vPosition;
varying float vThickness;
varying vec2 vUv;
varying vec3 vGlassColor;

uniform mat4 modelMatrix;
uniform mat4 projectionMatrix;
uniform float envReflection@Glass.envReflection;
uniform sampler2D map@Post.backMap;
uniform sampler2D noiseMap@BlueNoise.map;
uniform float colorFactor@Glass.colorFactor;
uniform float iorStart@Glass.iorStart;
uniform float iorDelta@Glass.iorDelta;
uniform float reflectionIridescence@Glass.reflectionIridescence;
uniform float colorBoost@Glass.colorBoost;
uniform float decayFactor@Glass.decayFactor;
uniform float maxColorValue@Glass.maxColorValue;
uniform float useTransmittance@Glass.useTransmittance;
uniform float fringeCurve@Glass.fringeCurve;
uniform float fringeMix@Glass.fringeMix;
uniform vec3 fringeColor@Glass.fringeColor;
uniform float uvShiftFactor@Glass.uvShiftFactor;
uniform float seconds@Tick.seconds;
uniform float colorCurve@Glass.colorCurve;
uniform float colorCurveR@Glass.colorCurveR;
uniform float colorCurveG@Glass.colorCurveG;
uniform float colorCurveB@Glass.colorCurveB;

uniform sampler2D envMap@Env.map;

#define oneOverPi 0.3183098861837907
vec3 getEnvColor(vec3 ray) {
  vec2 uv = vec2(atan(ray.x, ray.z) * 0.5, asin(ray.y));
  uv = uv * oneOverPi + 0.5;
  uv.x = fract(uv.x);
  vec3 color = texture2D(envMap, uv).rgb;
  color = 1. - exp(-0.1 * color);
  return color;
}
uniform sampler2D colorsMap@ColorsMap.map;

vec3 boostSaturation(vec3 rgb, float boost) {
  float maxC = max(rgb.r, max(rgb.g, rgb.b));
  float minC = min(rgb.r, min(rgb.g, rgb.b));
  float S = (maxC > 1e-6) ? ((maxC - minC) / maxC) : 0.;
  float Sp = clamp(S * boost, 0., 1.);
  float ratio = (S > 1e-6) ? (Sp / S) : 0.;
  return clamp(maxC - (maxC - rgb) * ratio, 0., 1.);
}

vec3 mixToColor(float f) {
  return texture2D(colorsMap, vec2(f, 0.)).rgb;
}

vec3 getIridescence(vec3 rd, vec3 n) {
  float thickness = 1. - abs(dot(n, rd));
  return texture2D(colorsMap, vec2(thickness * 0.3 + 0.08, 1.)).rgb;
}
#ifdef USE_NORMAL_MAP
  uniform sampler2D normalMap;
#endif

vec3 getNormal() {
  vec3 N = normalize(vNormal);
  #ifdef USE_NORMAL_MAP
    vec2 uv = vUv;
    vec3 mapN = normalize(texture2D(normalMap, uv).xyz * 2.0 - 1.0);
    vec3 T = normalize(vTangent);
    vec3 B = normalize(cross(N, T));
    mat3 tbn = mat3(T, B, N);
    vec3 newN = normalize(tbn * mapN);
    return normalize(mat3(modelMatrix) * newN);
  #else
    return normalize(mat3(modelMatrix) * N);
  #endif
}

float fresnelSchlick(vec3 ray, vec3 normal) {
  float cosTheta = abs(dot(ray, normal));
  return 0.04 + 0.96 * pow(1. - cosTheta, 5.);
}

float fresnelReflectivity(vec3 rd, vec3 n, float n1, float n2) {
  vec3 rfr = refract(rd, n, n1 / n2);

  if (dot(rfr, rfr) == 0.) return 1.;

  float cos1 = -dot(rd, n);
  float cos2 = -dot(rfr, n);

  float n1c1 = n1 * cos1;
  float n2c1 = n2 * cos1;
  float n1c2 = n1 * cos2;
  float n2c2 = n2 * cos2;

  float rs = (n1c1 - n2c2) / (n1c1 + n2c2);
  float rp = (n1c2 - n2c1) / (n1c2 + n2c1);

  return 0.5 * (rs * rs + rp * rp);
}

void main() {
  vec3 normal = getNormal();
  vec3 viewDirection = normalize(vPosition - cameraPosition);

  vec3 refractionA = refract(viewDirection, normal, 1. / iorStart);
  vec3 refractionB = refract(viewDirection, normal, 1. / (iorStart + iorDelta));

  
  float transmittance = 1. - useTransmittance * fresnelSchlick(refractionA, normal);

  vec4 clipA = projectionMatrix * viewMatrix * vec4(vPosition + refractionA * vThickness, 1.0);
  clipA.xy /= 1.25;
  vec4 clipB = projectionMatrix * viewMatrix * vec4(vPosition + refractionB * vThickness, 1.0);
  clipB.xy /= 1.25;

  vec2 uvA = saturate(clipA.xy / clipA.w * 0.5 + 0.5);
  vec2 uvB = saturate(clipB.xy / clipB.w * 0.5 + 0.5);
  vec2 noiseUv = fract(uvA * 777. + seconds);

  vec3 color = vec3(0.0);
  vec3 palAccum = vec3(0.0);

  float dq = 1. / float(samplesCount);
  float blue = texture2D(noiseMap, noiseUv).r;
  float mixFactor = blue * dq;
  vec2 dUv = (uvB - uvA) * dq * uvShiftFactor;
  vec2 uv = uvA + dUv * blue;
  vec3 texel;
  vec3 pal;
  #pragma unroll_loop_start
  for (int i = 0; i < samplesCount; i++) {
    texel = texture2D(map, uv).rgb;

    pal = mixToColor(mixFactor);
    palAccum += pal;
    color += texel * pal;

    uv += dUv;
    mixFactor += dq;
  }
  #pragma unroll_loop_end

  color *= transmittance / palAccum;

  
  
  
  
  
  
  
  

  float fringeness = fringeMix * pow(saturate(1. - abs(dot(viewDirection, normal))), fringeCurve);
  color = mix(color, fringeColor, fringeness);

  float luminance = dot(color, vec3(0.2126, 0.7152, 0.0722));
  color = (color - luminance) * colorBoost + luminance;
  color = max(color, 0.);

  float decay = exp(-vThickness * decayFactor);
  color *= mix(vGlassColor, vec3(1.), decay);

  vec3 iridescence = getIridescence(viewDirection, normal) - 1.; 
  iridescence = reflectionIridescence * iridescence + 1.;

  color *= colorFactor;
  if (color.r < 1.) {
    color.r = pow(color.r, colorCurve * colorCurveR);
  }
  if (color.g < 1.) {
    color.g = pow(color.g, colorCurve * colorCurveG);
  }
  if (color.b < 1.) {
    color.b = pow(color.b, colorCurve * colorCurveB);
  }

  float fresnel = fresnelSchlick(viewDirection, normal);
  vec3 ray = reflect(viewDirection, normal);
  color += getEnvColor(ray) * envReflection * fresnel * iridescence;

  color = clamp(color, 0., maxColorValue);

  gl_FragColor = vec4(color, @sweet alpha);
}`;

// w1 — GlassFront vertex（用 @sweet padding; 宏）
export const GLASS_FRONT_VERT = `attribute float _thickness;
attribute float _peaks;
attribute vec3 tangent;

varying float vThickness;
varying vec3 vNormal;
varying vec3 vTangent;
varying vec3 vPosition;
varying vec2 vUv;
varying vec3 vGlassColor;

#include <skinning_pars_vertex>

uniform float distancesFactor@Glass.distancesFactor;
uniform float resetDistances@Glass.resetDistances;
uniform vec3 baseColor@Glass.color;
uniform vec3 peaksColor@Glass.peaksColor;
uniform float peaksFactor@Glass.peaksFactor;

void main() {
  vThickness = mix(_thickness * distancesFactor, 0.1, resetDistances);
  vUv = uv;
  vGlassColor = mix(baseColor, peaksColor, clamp(_peaks * peaksFactor, 0., 1.));

  vec3 transformed = position;
  vec3 objectNormal = normal;
  vec3 objectTangent = tangent;

  #include <skinbase_vertex>
  #include <skinnormal_vertex>
  #include <skinning_vertex>

  vNormal = objectNormal;
  vTangent = objectTangent;

  vec4 worldPosition = modelMatrix * vec4(transformed, 1.0);
  vPosition = worldPosition.xyz;

  gl_Position = projectionMatrix * viewMatrix * worldPosition;
  @sweet padding;
}`;

// iie — IconMaterial fragment（假 matcap，L54841-54851 逐字）
export const ICON_FRAG = `#define saturate(x) clamp(x, 0., 1.)

varying vec3 vPosition;
varying vec3 vNormal;

void main() {
  vec3 ray = normalize(cameraPosition - vPosition);
  ray = reflect(ray, vNormal);
  vec3 color = abs(ray.z) * 0.2 + vec3(0.5);
  gl_FragColor = vec4(color, 1.);
}`;
// rie — IconMaterial vertex（L54852-54861 逐字；L54860 硬编码 /= 1.25，同 y1 怪癖）
export const ICON_VERT = `varying vec3 vPosition;
varying vec3 vNormal;

void main() {
  vec4 worldPosition = modelMatrix * vec4(position, 1.0);
  vPosition = worldPosition.xyz;
  vNormal = mat3(modelMatrix) * normal;
  gl_Position = projectionMatrix * viewMatrix * worldPosition;
  gl_Position.xy /= 1.25;
}`;

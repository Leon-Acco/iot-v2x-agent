// dark water backdrop (gradient + caustics + waves) with an iridescent
// brush-stroke mouse trail: drops stretch along the stroke direction,
// shimmer with rainbow tint, and drift slowly like ink on water.
import { Color, Mesh, PlaneGeometry, ShaderMaterial, Vector2, Vector4 } from "three";
import { injectUniforms, setProvider } from "./core";

const WATER_VERT = `
varying vec2 vUv;
void main() {
  vUv = uv;
  gl_Position = vec4(position.xy, 0.9999, 1.0);
}
`;

const WATER_FRAG = `
varying vec2 vUv;
uniform float seconds@Tick.seconds;
uniform vec2 resolution@Viewport.resolution;
uniform vec4 ripple0@Water.ripple0;
uniform vec4 ripple1@Water.ripple1;
uniform vec4 ripple2@Water.ripple2;
uniform vec4 ripple3@Water.ripple3;
uniform vec3 inkColor0@Water.inkColor0;
uniform vec3 inkColor3@Water.inkColor3;
uniform vec3 inkColor1@Water.inkColor1;
uniform vec3 inkColor2@Water.inkColor2;
uniform sampler2D dyeMap@Fluid.dye;

float hash(vec2 p) {
  return fract(sin(dot(p, vec2(127.1, 311.7))) * 43758.5453);
}
float vnoise(vec2 p) {
  vec2 i = floor(p);
  vec2 f = fract(p);
  f = f * f * (3.0 - 2.0 * f);
  return mix(
    mix(hash(i), hash(i + vec2(1.0, 0.0)), f.x),
    mix(hash(i + vec2(0.0, 1.0)), hash(i + vec2(1.0, 1.0)), f.x),
    f.y
  );
}

// thin bright filaments where the wave field crosses zero
float caustic(vec2 uv, float t) {
  vec2 p = uv * 9.0;
  float a = sin(p.x * 1.3 + t * 0.7) + sin(p.y * 1.1 - t * 0.5);
  float b = sin((p.x + p.y) * 0.9 + t * 0.8) + sin(length(p - 3.5) * 1.6 - t * 0.6);
  float c = sin(a + b);
  return pow(1.0 - abs(c), 6.0);
}

// fluid ribbon drop: elongated along the stroke, fine flowing streaks
// (no blocky value-noise), dark wispy interior, iridescent rim fringe.
// xy = uv origin, z = birth seconds, w = strength.
vec4 brushDrop(vec2 uv, vec4 r, vec3 col, vec2 dir, float t, float aspect) {
  float age = t - r.z;
  if (age < 0.0 || age > 5.0) return vec4(0.0);
  vec2 origin = r.xy + vec2(-0.004, 0.002) * age;
  vec2 dd = uv - origin;
  dd.x *= aspect;
  vec2 tang = normalize(dir + vec2(1e-4, 0.0));
  vec2 norm = vec2(-tang.y, tang.x);
  vec2 b = vec2(dot(dd, tang), dot(dd, norm));
  float dist = length(b * vec2(0.75, 2.2));
  float radius = 0.025 + age * 0.075;
  // fine streaks flow along the stroke direction
  float streak = vnoise(vec2(b.x * 6.0 - age * 1.5, b.y * 70.0) + r.xy * 53.0);
  float wisp = vnoise(vec2(b.x * 12.0 - age * 2.0, b.y * 34.0) + r.xy * 91.0);
  float body = smoothstep(radius, radius * 0.3, dist);
  body *= 0.35 + 0.65 * wisp * (0.5 + 0.5 * streak);
  // thin rim band -> iridescent fringe like the fluid reference
  float rim = smoothstep(radius, radius * 0.75, dist) * (1.0 - smoothstep(radius * 0.75, radius * 0.4, dist));
  float fade = exp(-age * 0.9) * smoothstep(0.0, 0.06, age);
  float alpha = clamp(body + rim * 0.9, 0.0, 1.0) * fade * r.w;
  vec3 fringe = 0.5 + 0.5 * sin(vec3(0.0, 2.1, 4.2) + b.y * 16.0 + b.x * 6.0 + age);
  vec3 inkCol = col * 0.30 * (0.3 + 0.7 * streak) + col * rim * (0.5 + 0.9 * fringe);
  return vec4(inkCol * alpha, alpha);
}

void main() {
  vec2 uv = vUv;
  float aspect = resolution.x / max(resolution.y, 1.0);
  // pure black gradient
  vec3 top = vec3(0.010, 0.012, 0.016);
  vec3 bottom = vec3(0.002, 0.003, 0.005);
  vec3 color = mix(bottom, top, uv.y);
  // vignette
  float vig = smoothstep(1.15, 0.35, length((uv - 0.5) * vec2(aspect, 1.0)));
  color *= 0.70 + 0.30 * vig;
  // GPU fluid dye (additive on dark)
  color += texture2D(dyeMap, uv).rgb;
  gl_FragColor = vec4(color, 1.0);
}
`;

export class WaterBg extends Mesh {
  constructor() {
    // seed slots so uniforms always have proper values
    setProvider("Water.ripple0", new Vector4(0.5, 0.5, -10, 1));
    setProvider("Water.ripple1", new Vector4(0.5, 0.5, -10, 1));
    setProvider("Water.ripple2", new Vector4(0.5, 0.5, -10, 1));
    setProvider("Water.ripple3", new Vector4(0.5, 0.5, -10, 1));
    setProvider("Water.inkColor0", new Color(0xe04fa0));
    setProvider("Water.inkColor1", new Color(0x8b4fe8));
    setProvider("Water.inkColor2", new Color(0xf28c28));
    setProvider("Water.inkColor3", new Color(0x3f6fe0));
    setProvider("Water.inkDir0", new Vector2(1, 0));
    setProvider("Water.inkDir1", new Vector2(1, 0));
    setProvider("Water.inkDir2", new Vector2(1, 0));
    setProvider("Water.inkDir3", new Vector2(1, 0));
    super(
      new PlaneGeometry(2, 2),
      new ShaderMaterial(
        injectUniforms({
          vertexShader: WATER_VERT,
          fragmentShader: WATER_FRAG,
          depthTest: false,
          depthWrite: false,
        } as any),
      ),
    );
    this.frustumCulled = false;
  }
}

// GPU fluid simulation (Navier-Stokes), adapted from Pavel Dobryakovs
// classic MIT-licensed WebGL-Fluid-Simulation, ported onto three.js RTs.
// Pipeline per frame: curl -> vorticity -> divergence -> pressure (jacobi)
// -> gradient subtract -> advect velocity -> advect dye. Output: dye texture.
import {
  HalfFloatType,
  LinearFilter,
  Mesh,
  NoBlending,
  OrthographicCamera,
  PlaneGeometry,
  RGBAFormat,
  Scene,
  ShaderMaterial,
  Vector2,
  Vector3,
  WebGLRenderer,
  WebGLRenderTarget,
} from "three";

const VERT = `
varying vec2 vUv;
void main() {
  vUv = uv;
  gl_Position = vec4(position.xy, 0.0, 1.0);
}
`;

const CLEAR_FRAG = `
varying vec2 vUv;
uniform sampler2D uTexture;
uniform float value;
void main() {
  gl_FragColor = value * texture2D(uTexture, vUv);
}
`;

const SPLAT_FRAG = `
varying vec2 vUv;
uniform sampler2D uTarget;
uniform vec2 point;
uniform vec3 color;
uniform float radius;
uniform float aspectRatio;
void main() {
  vec2 p = vUv - point;
  p.x *= aspectRatio;
  vec3 base = texture2D(uTarget, vUv).xyz;
  vec3 splat = exp(-dot(p, p) / radius) * color;
  gl_FragColor = vec4(base + splat, 1.0);
}
`;

const ADVECTION_FRAG = `
varying vec2 vUv;
uniform sampler2D uVelocity;
uniform sampler2D uSource;
uniform vec2 texelSize;
uniform float dt;
uniform float dissipation;
void main() {
  vec2 coord = vUv - dt * texture2D(uVelocity, vUv).xy * texelSize;
  vec4 result = texture2D(uSource, coord);
  float decay = 1.0 + dissipation * dt;
  gl_FragColor = result / decay;
}
`;

const DIVERGENCE_FRAG = `
varying vec2 vUv;
uniform sampler2D uVelocity;
uniform vec2 texelSize;
void main() {
  float L = texture2D(uVelocity, vUv - vec2(texelSize.x, 0.0)).x;
  float R = texture2D(uVelocity, vUv + vec2(texelSize.x, 0.0)).x;
  float B = texture2D(uVelocity, vUv - vec2(0.0, texelSize.y)).y;
  float T = texture2D(uVelocity, vUv + vec2(0.0, texelSize.y)).y;
  vec2 C = texture2D(uVelocity, vUv).xy;
  if (vUv.x - texelSize.x < 0.0) { L = -C.x; }
  if (vUv.x + texelSize.x > 1.0) { R = -C.x; }
  if (vUv.y - texelSize.y < 0.0) { B = -C.y; }
  if (vUv.y + texelSize.y > 1.0) { T = -C.y; }
  gl_FragColor = vec4(0.5 * (R - L + T - B), 0.0, 0.0, 1.0);
}
`;

const CURL_FRAG = `
varying vec2 vUv;
uniform sampler2D uVelocity;
uniform vec2 texelSize;
void main() {
  float L = texture2D(uVelocity, vUv - vec2(texelSize.x, 0.0)).y;
  float R = texture2D(uVelocity, vUv + vec2(texelSize.x, 0.0)).y;
  float B = texture2D(uVelocity, vUv - vec2(0.0, texelSize.y)).x;
  float T = texture2D(uVelocity, vUv + vec2(0.0, texelSize.y)).x;
  float vorticity = R - L - T + B;
  gl_FragColor = vec4(0.5 * vorticity, 0.0, 0.0, 1.0);
}
`;

const VORTICITY_FRAG = `
varying vec2 vUv;
uniform sampler2D uVelocity;
uniform sampler2D uCurl;
uniform vec2 texelSize;
uniform float curl;
uniform float dt;
void main() {
  float L = texture2D(uCurl, vUv - vec2(texelSize.x, 0.0)).x;
  float R = texture2D(uCurl, vUv + vec2(texelSize.x, 0.0)).x;
  float B = texture2D(uCurl, vUv - vec2(0.0, texelSize.y)).x;
  float T = texture2D(uCurl, vUv + vec2(0.0, texelSize.y)).x;
  float C = texture2D(uCurl, vUv).x;
  vec2 force = 0.5 * vec2(abs(T) - abs(B), abs(R) - abs(L));
  force /= length(force) + 0.0001;
  force *= curl * C;
  force.y *= -1.0;
  vec2 velocity = texture2D(uVelocity, vUv).xy;
  velocity += force * dt;
  velocity = clamp(velocity, vec2(-1000.0), vec2(1000.0));
  gl_FragColor = vec4(velocity, 0.0, 1.0);
}
`;

const PRESSURE_FRAG = `
varying vec2 vUv;
uniform sampler2D uPressure;
uniform sampler2D uDivergence;
uniform vec2 texelSize;
void main() {
  float L = texture2D(uPressure, vUv - vec2(texelSize.x, 0.0)).x;
  float R = texture2D(uPressure, vUv + vec2(texelSize.x, 0.0)).x;
  float B = texture2D(uPressure, vUv - vec2(0.0, texelSize.y)).x;
  float T = texture2D(uPressure, vUv + vec2(0.0, texelSize.y)).x;
  float divergence = texture2D(uDivergence, vUv).x;
  float pressure = (L + R + B + T - divergence) * 0.25;
  gl_FragColor = vec4(pressure, 0.0, 0.0, 1.0);
}
`;

const GRADIENT_SUBTRACT_FRAG = `
varying vec2 vUv;
uniform sampler2D uPressure;
uniform sampler2D uVelocity;
uniform vec2 texelSize;
void main() {
  float L = texture2D(uPressure, vUv - vec2(texelSize.x, 0.0)).x;
  float R = texture2D(uPressure, vUv + vec2(texelSize.x, 0.0)).x;
  float B = texture2D(uPressure, vUv - vec2(0.0, texelSize.y)).x;
  float T = texture2D(uPressure, vUv + vec2(0.0, texelSize.y)).x;
  vec2 velocity = texture2D(uVelocity, vUv).xy;
  velocity -= vec2(R - L, T - B);
  gl_FragColor = vec4(velocity, 0.0, 1.0);
}
`;

export interface FluidConfig {
  simResolution: number;
  dyeResolution: number;
  densityDissipation: number;
  velocityDissipation: number;
  pressure: number;
  pressureIterations: number;
  curl: number;
  splatRadius: number;
  splatForce: number;
}

export const DEFAULT_FLUID_CONFIG: FluidConfig = {
  simResolution: 128,
  dyeResolution: 768,
  densityDissipation: 1.9,
  velocityDissipation: 0.55,
  pressure: 0.8,
  pressureIterations: 16,
  curl: 8,
  splatRadius: 0.0006,
  splatForce: 2600,
};

function createRT(w: number, h: number) {
  return new WebGLRenderTarget(w, h, {
    type: HalfFloatType,
    format: RGBAFormat,
    minFilter: LinearFilter,
    magFilter: LinearFilter,
    depthBuffer: false,
    stencilBuffer: false,
    generateMipmaps: false,
  });
}

class DoubleRT {
  read: WebGLRenderTarget;
  write: WebGLRenderTarget;
  constructor(w: number, h: number) {
    this.read = createRT(w, h);
    this.write = createRT(w, h);
  }
  swap() {
    const t = this.read;
    this.read = this.write;
    this.write = t;
  }
}

export class FluidSim {
  config: FluidConfig;
  renderer: WebGLRenderer;
  velocity: DoubleRT;
  dye: DoubleRT;
  dyeOut: WebGLRenderTarget;
  pressure: DoubleRT;
  curlRT: WebGLRenderTarget;
  divergenceRT: WebGLRenderTarget;
  scene = new Scene();
  cam = new OrthographicCamera(-1, 1, 1, -1, 0, 1);
  quad: Mesh;
  matClear: ShaderMaterial;
  matSplat: ShaderMaterial;
  matAdvection: ShaderMaterial;
  matDivergence: ShaderMaterial;
  matCurl: ShaderMaterial;
  matVorticity: ShaderMaterial;
  matPressure: ShaderMaterial;
  matGradient: ShaderMaterial;
  simTexel: Vector2;
  dyeTexel: Vector2;

  constructor(renderer: WebGLRenderer, config: Partial<FluidConfig> = {}) {
    this.config = { ...DEFAULT_FLUID_CONFIG, ...config };
    this.renderer = renderer;
    const sim = this.config.simResolution;
    const dye = this.config.dyeResolution;
    this.velocity = new DoubleRT(sim, sim);
    this.dye = new DoubleRT(dye, dye);
    this.dyeOut = createRT(dye, dye);
    this.pressure = new DoubleRT(sim, sim);
    this.curlRT = createRT(sim, sim);
    this.divergenceRT = createRT(sim, sim);
    this.simTexel = new Vector2(1 / sim, 1 / sim);
    this.dyeTexel = new Vector2(1 / dye, 1 / dye);
    const base = { vertexShader: VERT, blending: NoBlending, depthTest: false, depthWrite: false };
    const U = (o: Record<string, { value: unknown }>) => o;
    this.matClear = new ShaderMaterial({ ...base, fragmentShader: CLEAR_FRAG,
      uniforms: U({ uTexture: { value: null }, value: { value: this.config.pressure } }) });
    this.matSplat = new ShaderMaterial({ ...base, fragmentShader: SPLAT_FRAG,
      uniforms: U({ uTarget: { value: null }, point: { value: new Vector2() }, color: { value: new Vector3() }, radius: { value: this.config.splatRadius }, aspectRatio: { value: 1 } }) });
    this.matAdvection = new ShaderMaterial({ ...base, fragmentShader: ADVECTION_FRAG,
      uniforms: U({ uVelocity: { value: null }, uSource: { value: null }, texelSize: { value: this.simTexel }, dt: { value: 0 }, dissipation: { value: 0 } }) });
    this.matDivergence = new ShaderMaterial({ ...base, fragmentShader: DIVERGENCE_FRAG,
      uniforms: U({ uVelocity: { value: null }, texelSize: { value: this.simTexel } }) });
    this.matCurl = new ShaderMaterial({ ...base, fragmentShader: CURL_FRAG,
      uniforms: U({ uVelocity: { value: null }, texelSize: { value: this.simTexel } }) });
    this.matVorticity = new ShaderMaterial({ ...base, fragmentShader: VORTICITY_FRAG,
      uniforms: U({ uVelocity: { value: null }, uCurl: { value: null }, texelSize: { value: this.simTexel }, curl: { value: this.config.curl }, dt: { value: 0 } }) });
    this.matPressure = new ShaderMaterial({ ...base, fragmentShader: PRESSURE_FRAG,
      uniforms: U({ uPressure: { value: null }, uDivergence: { value: null }, texelSize: { value: this.simTexel } }) });
    this.matGradient = new ShaderMaterial({ ...base, fragmentShader: GRADIENT_SUBTRACT_FRAG,
      uniforms: U({ uPressure: { value: null }, uVelocity: { value: null }, texelSize: { value: this.simTexel } }) });
    this.quad = new Mesh(new PlaneGeometry(2, 2), this.matClear);
    this.quad.frustumCulled = false;
    this.scene.add(this.quad);
  }

  // stable output texture (dye double-buffer swaps internally)
  get dyeTexture() {
    return this.dyeOut.texture;
  }

  private blit(target: WebGLRenderTarget | null, material: ShaderMaterial) {
    this.quad.material = material;
    this.renderer.setRenderTarget(target);
    this.renderer.render(this.scene, this.cam);
  }

  // inject a velocity + dye splat at uv (0..1, y up)
  splat(x: number, y: number, dx: number, dy: number, r: number, g: number, b: number, aspect: number) {
    const m = this.matSplat;
    m.uniforms.aspectRatio.value = aspect;
    m.uniforms.radius.value = this.config.splatRadius;
    m.uniforms.point.value.set(x, y);
    m.uniforms.color.value.set(dx, dy, 0);
    m.uniforms.uTarget.value = this.velocity.read.texture;
    this.blit(this.velocity.write, m);
    this.velocity.swap();
    m.uniforms.color.value.set(r, g, b);
    m.uniforms.uTarget.value = this.dye.read.texture;
    this.blit(this.dye.write, m);
    this.dye.swap();
  }

  step(dt: number) {
    const cfg = this.config;
    dt = Math.min(dt, 1 / 30);
    // curl + vorticity confinement
    this.matCurl.uniforms.uVelocity.value = this.velocity.read.texture;
    this.blit(this.curlRT, this.matCurl);
    this.matVorticity.uniforms.uVelocity.value = this.velocity.read.texture;
    this.matVorticity.uniforms.uCurl.value = this.curlRT.texture;
    this.matVorticity.uniforms.dt.value = dt;
    this.blit(this.velocity.write, this.matVorticity);
    this.velocity.swap();
    // divergence + pressure solve
    this.matDivergence.uniforms.uVelocity.value = this.velocity.read.texture;
    this.blit(this.divergenceRT, this.matDivergence);
    this.matClear.uniforms.uTexture.value = this.pressure.read.texture;
    this.blit(this.pressure.write, this.matClear);
    this.pressure.swap();
    for (let i = 0; i < cfg.pressureIterations; i++) {
      this.matPressure.uniforms.uDivergence.value = this.divergenceRT.texture;
      this.matPressure.uniforms.uPressure.value = this.pressure.read.texture;
      this.blit(this.pressure.write, this.matPressure);
      this.pressure.swap();
    }
    // gradient subtract
    this.matGradient.uniforms.uPressure.value = this.pressure.read.texture;
    this.matGradient.uniforms.uVelocity.value = this.velocity.read.texture;
    this.blit(this.velocity.write, this.matGradient);
    this.velocity.swap();
    // advect velocity then dye
    const adv = this.matAdvection;
    adv.uniforms.texelSize.value = this.simTexel;
    adv.uniforms.dt.value = dt;
    adv.uniforms.dissipation.value = cfg.velocityDissipation;
    adv.uniforms.uVelocity.value = this.velocity.read.texture;
    adv.uniforms.uSource.value = this.velocity.read.texture;
    this.blit(this.velocity.write, adv);
    this.velocity.swap();
    adv.uniforms.dissipation.value = cfg.densityDissipation;
    adv.uniforms.uSource.value = this.dye.read.texture;
    this.blit(this.dye.write, adv);
    this.dye.swap();
    // copy to the stable output target
    this.matClear.uniforms.value.value = 1;
    this.matClear.uniforms.uTexture.value = this.dye.read.texture;
    this.blit(this.dyeOut, this.matClear);
    this.matClear.uniforms.value.value = this.config.pressure;
    this.renderer.setRenderTarget(null);
  }
}

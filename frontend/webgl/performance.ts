// Performance（Pt L50345-50417）+ PerformanceTester（Ip L50328-50344）逐字移植。
// 七级降档表：hyperSampling/blurFire → backDispersion/highFrontSamples → msaa →
// screenPadding → normalDPR → frontDispersion → reflector；value = (idx >= level)。
// 有意偏差（REBUILD_PLAN §6 怪癖⑤）：?__probe 下不调用 autoAdaptation，
// 固定 optimizationLevel=2 —— 在 index.ts 装配处跳过，本类保持逐字。
import { events, appSettings, Tick, type TickFrame } from "./core";

// $d（L50327）
const delay = (ms: number) => new Promise((r) => setTimeout(r, ms));
// A1（L50327）
const TEST_FRAMES = 5;

// Ip —— 测 5 帧平均 FPS（L50328-50344）
class PerformanceTester {
  static event = "PerformanceTester.event";
  startSeconds = 0;
  framesPassed = -5;
  onAfterRender = (frame: TickFrame) => {
    if (
      (this.framesPassed++,
      this.framesPassed === 0 && (this.startSeconds = frame.seconds),
      this.framesPassed === TEST_FRAMES)
    ) {
      const fps = TEST_FRAMES / (frame.seconds - this.startSeconds);
      events.off(Tick.afterRenderEvent, this.onAfterRender);
      events.dispatch(PerformanceTester.event, fps);
    }
  };
  test = async () => (
    (this.framesPassed = -5),
    events.on(Tick.afterRenderEvent, this.onAfterRender),
    new Promise<number>((resolve) => {
      const onEvent = (fps: number) => {
        events.off(PerformanceTester.event, onEvent);
        resolve(fps);
      };
      events.on(PerformanceTester.event, onEvent);
    })
  );
}

// Pt —— 自适应画质（L50345-50417）
export class Performance {
  static initialOptimizationDoneEvent = "Performance.initialOptimizationDoneEvent";
  static hyperSamplingEvent = "Performance.hyperSamplingEvent";
  static blurFireEvent = "Performance.blurFireEvent";
  static backDispersionEvent = "Performance.backDispersionEvent";
  static highFrontSamplesEvent = "Performance.highFrontSamplesEvent";
  static screenPaddingEvent = "Performance.screenPaddingEvent";
  static msaaEvent = "Performance.msaaEvent";
  static normalDPREvent = "Performance.normalDPREvent";
  static frontDispersionEvent = "Performance.frontDispersionEvent";
  static reflectorEvent = "Performance.reflectorEvent";
  static optimizationLevel = 2;
  tester = new PerformanceTester();
  canBeBetter = true;
  initialOptimizationDone = false;
  #maxFPS = 30;
  #fps = 0;
  levels = [
    [Performance.hyperSamplingEvent, Performance.blurFireEvent],
    [Performance.backDispersionEvent, Performance.highFrontSamplesEvent],
    Performance.msaaEvent,
    Performance.screenPaddingEvent,
    Performance.normalDPREvent,
    Performance.frontDispersionEvent,
    Performance.reflectorEvent,
  ].map((event, i) => ({
    event,
    value: i >= Performance.optimizationLevel,
  }));
  // devMode GUI binding（Eo）：rebuild devMode 恒 false，保留字段照抄
  binding: { refresh: () => void } | null = null;
  bindingValue = Performance.optimizationLevel;
  constructor() {
    // 源站此处 devMode 时建 tweakpane binding（L50367-50371）；devMode=false 为空操作
  }
  measureMaxFPS = async () => {
    if (this.#maxFPS > 30) return;
    const fps = Math.floor(await this.tester.test());
    this.#maxFPS = Math.max(30, fps);
    this.#fps = fps;
    if (appSettings.devMode) console.log(`Max FPS: ${this.#maxFPS}`);
  };
  autoAdaptation = async () => {
    await delay(20);
    this.setOptimizationLevel(Performance.optimizationLevel);
    await delay(480);
    this.go();
  };
  go = async (): Promise<void> => {
    if (document.visibilityState !== "visible" && !document.hasFocus()) {
      if (appSettings.devMode)
        console.log(
          "Performance: Page is not visible and not focused, waiting 10 or 0.1 seconds...",
        );
      await delay(this.initialOptimizationDone ? 1e4 : 100);
      this.go();
      return;
    }
    this.#fps = await this.tester.test();
    if (appSettings.devMode)
      console.log(
        `Optimization level ${Performance.optimizationLevel} fps: ${((this.#fps / this.#maxFPS) * 100).toFixed(2)}% of ${this.#maxFPS}`,
      );
    if (this.#fps < this.#maxFPS * 0.8) {
      this.canBeBetter = false;
      if (Performance.optimizationLevel < this.levels.length) {
        this.setOptimizationLevel(Performance.optimizationLevel + 1);
        this.go();
        return;
      }
      return;
    }
    if (this.#fps >= this.#maxFPS)
      if (this.canBeBetter) {
        this.#maxFPS = Math.floor(this.#fps) - 1;
        if (Performance.optimizationLevel > 0) {
          this.setOptimizationLevel(Performance.optimizationLevel - 1);
          this.go();
          return;
        }
      } else this.#maxFPS = Math.floor(this.#fps);
    if (!this.initialOptimizationDone) {
      this.initialOptimizationDone = true;
      events.dispatch(Performance.initialOptimizationDoneEvent);
    }
    await delay(1e4);
    this.go();
  };
  setOptimizationLevel = (level: number) => {
    if (Performance.optimizationLevel === level) return;
    if (appSettings.devMode) console.log(`Setting optimization level to ${level}`);
    Performance.optimizationLevel = level;
    this.bindingValue = level;
    this.binding?.refresh();
    this.levels.forEach((entry, i) => {
      const value = i >= level;
      if (value === entry.value) return;
      entry.value = value;
      Array.isArray(entry.event)
        ? entry.event.forEach((e) => events.dispatch(e, value))
        : events.dispatch(entry.event as string, value);
    });
  };
  get maxFPS() {
    return this.#maxFPS;
  }
  get fps() {
    return this.#fps;
  }
}

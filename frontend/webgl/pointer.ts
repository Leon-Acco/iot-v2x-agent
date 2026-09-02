// Pointer（Ot L49791-49831）逐字移植：body 原生事件（_a.load L56471-56501，
// 在 index.ts 挂接）转成 rawEvent/rawDown/rawUp，本类归一化成
// pointer(0-1)/pointerNDC(-1..1)/pointer3；射线器默认开启（L49798 =!0），
// 订阅自身 ndcEvent 与 dr.seekEvent 更新 raycaster。
import { Raycaster, Vector2, Vector3 } from "three";
import { app, events, setProvider } from "./core";

export class Pointer {
  static rawEvent = "pointer.raw";
  static rawDownEvent = "pointer.raw.down";
  static rawUpEvent = "pointer.raw.up";
  static event = "pointer";
  static ndcEvent = "pointer.ndc";
  static raycasterEvent = "pointer.raycaster";
  static clickEvent = "pointer.click";
  static provider = "pointer";
  static v3Provider = "pointer.v3";
  static raycasterProvider = "pointer.raycaster";
  static raycasterOriginProvider = "pointer.origin";
  static raycasterDirectionProvider = "pointer.direction";
  pointer = new Vector2();
  pointerNDC = new Vector2();
  pointer3 = new Vector3();
  raycaster?: Raycaster;
  constructor({ enableRaycaster = true }: { enableRaycaster?: boolean } = {}) {
    this.updatePointer({
      x: app.viewport.x * 0.5,
      y: app.viewport.y * 0.5,
    }),
      setProvider(Pointer.provider, this.pointer),
      setProvider(Pointer.v3Provider, this.pointer3),
      events.on(Pointer.rawEvent, this.updatePointer),
      events.on(Pointer.rawDownEvent, this.onDown),
      events.on(Pointer.rawUpEvent, this.onUp),
      enableRaycaster &&
        ((this.raycaster = new Raycaster()),
        setProvider(
          Pointer.raycasterOriginProvider,
          this.raycaster.ray.origin,
        ),
        setProvider(
          Pointer.raycasterDirectionProvider,
          this.raycaster.ray.direction,
        ),
        events.on(Pointer.ndcEvent, this.updateRaycaster));
  }
  updatePointer = ({ x, y }: { x: number; y: number }) => {
    this.pointer.set(x / app.viewport.x, 1 - y / app.viewport.y),
      this.pointerNDC.copy(this.pointer).multiplyScalar(2).subScalar(1),
      (this.pointer3.x = this.pointer.x),
      (this.pointer3.y = this.pointer.y),
      events.dispatch(Pointer.event, this.pointer),
      events.dispatch(Pointer.ndcEvent, this.pointerNDC);
  };
  updateRaycaster = () => {
    this.raycaster &&
      (this.raycaster.setFromCamera(this.pointerNDC, app.camera),
      events.dispatch(Pointer.raycasterEvent, this.raycaster));
  };
  onDown = () => {
    this.pointer3.z = 1;
  };
  onUp = () => {
    (this.pointer3.z = 0), events.dispatch(Pointer.clickEvent);
  };
}

// LayerController mini - crystals-only port: manual layers array.
// Original (L53008-53062) keeps a debugLayer tail and timeline-driven sorter;
// here layers = plain FIFO array consumed by RenderingPipeline.renderLayers.
import { events } from "./core";

export class LayerController {
  static addLayerEvent = "LayerController.addLayerEvent";
  static addDebugLayerEvent = "LayerController.addDebugLayerEvent";
  static removeLayerEvent = "LayerController.removeLayerEvent";
  static updateGlassEvent = "LayerController.updateGlassEvent";
  layers: any[] = [];
  constructor() {
    events.on(LayerController.addLayerEvent, this.addLayer);
    events.on(LayerController.removeLayerEvent, this.removeLayer);
  }
  addLayer = (obj: any) => {
    if (obj && !this.layers.includes(obj)) this.layers.push(obj);
  };
  removeLayer = (obj: any) => {
    this.layers = this.layers.filter((o) => o !== obj);
  };
}

<template>
  <div ref="el" class="viz-g6"></div>
</template>

<script setup>
const props = defineProps({
  data: { type: Object, default: null }
})
const el = ref(null)
let graph = null

onMounted(async () => {
  if (import.meta.server || !el.value) return
  const G6 = (await import('@antv/g6')).default
  const nodes = ((props.data && props.data.nodes) || []).map(n => ({
    id: String(n.id), label: n.label != null ? String(n.label) : String(n.id)
  }))
  const edges = ((props.data && props.data.edges) || []).map(e => ({
    source: String(e.source), target: String(e.target), label: e.label || ''
  }))
  graph = new G6.Graph({
    container: el.value,
    width: el.value.clientWidth || 680,
    height: 360,
    layout: { type: 'force', preventOverlap: true, nodeStrength: -60, linkDistance: 120 },
    modes: { default: ['drag-canvas', 'zoom-canvas', 'drag-node'] },
    defaultNode: {
      size: 30,
      style: { fill: '#17A05E', stroke: '#0B7A3C', lineWidth: 1.5 },
      labelCfg: { position: 'bottom', style: { fill: '#1A2B22', fontSize: 11 } }
    },
    defaultEdge: { style: { stroke: '#8AA294', lineWidth: 1.2, endArrow: true } }
  })
  graph.data({ nodes, edges })
  graph.render()
})

onBeforeUnmount(() => {
  if (graph) { graph.destroy(); graph = null }
})
</script>

<style scoped>
.viz-g6 { width: 100%; height: 360px; }
</style>

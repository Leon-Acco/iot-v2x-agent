<template>
  <div ref="el" class="viz-mermaid"></div>
</template>

<script setup>
const props = defineProps({
  code: { type: String, default: '' }
})
const el = ref(null)

onMounted(async () => {
  if (!props.code || import.meta.server) return
  try {
    const mermaid = (await import('mermaid')).default
    mermaid.initialize({ startOnLoad: false, theme: 'neutral', securityLevel: 'loose' })
    const id = 'viz-mmd-' + Math.random().toString(36).slice(2)
    const { svg } = await mermaid.render(id, props.code)
    if (el.value) el.value.innerHTML = svg
  } catch (e) {
    if (el.value) el.value.textContent = 'mermaid render failed: ' + (e && e.message ? e.message : e)
  }
})
</script>

<style scoped>
.viz-mermaid { width: 100%; overflow-x: auto; min-height: 120px; }
.viz-mermaid :deep(svg) { max-width: 100%; height: auto; }
</style>

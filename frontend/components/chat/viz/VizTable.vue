<template>
  <div class="viz-table-wrap">
    <table class="viz-table">
      <thead>
        <tr><th v-for="(c, i) in columns" :key="i">{{ c }}</th></tr>
      </thead>
      <tbody>
        <tr v-for="(r, ri) in rows" :key="ri">
          <td v-for="(c, ci) in r" :key="ci">{{ c == null ? '-' : c }}</td>
        </tr>
      </tbody>
    </table>
  </div>
</template>

<script setup>
defineProps({
  columns: { type: Array, default: () => [] },
  rows: { type: Array, default: () => [] }
})
</script>

<style scoped>
.viz-table-wrap { max-height: 320px; overflow: auto; }
.viz-table { width: 100%; border-collapse: collapse; font-size: 12px; }
.viz-table th {
  text-align: left; padding: 6px 10px; color: var(--text-3, #a1a1a1);
  font-weight: 500; border-bottom: 1px solid var(--border-default, #e5e7eb);
  position: sticky; top: 0; background: #fff;
}
.viz-table td { padding: 6px 10px; border-bottom: 1px solid var(--border-subtle, #f3f4f6); color: var(--text-2, #525252); }
</style>

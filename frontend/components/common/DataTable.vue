<template>
  <!-- 明细表：caption 表头 + 极浅行分隔 + hover 浅灰 -->
  <div class="data-table-wrap">
    <div v-if="!columns.length" class="empty">暂无数据</div>
    <template v-else>
      <table class="data-table">
        <thead>
          <tr>
            <th v-for="(c, i) in columns" :key="i">{{ c.display || c.name }}<span v-if="c.unit" class="unit">({{ c.unit }})</span></th>
          </tr>
        </thead>
        <tbody>
          <tr v-for="(row, ri) in displayRows" :key="ri">
            <td v-for="(c, ci) in columns" :key="ci">{{ row[ci] }}</td>
          </tr>
        </tbody>
      </table>
      <div class="table-footer">
        共 {{ rowCount != null ? rowCount : rows.length }} 行
        <span v-if="truncated" class="truncated">· 已截断，仅展示前 {{ rows.length }} 行</span>
      </div>
    </template>
  </div>
</template>

<script setup>
const props = defineProps({
  columns: { type: Array, default: () => [] },
  rows: { type: Array, default: () => [] },
  rowCount: { type: Number, default: null },
  truncated: { type: Boolean, default: false },
  maxRows: { type: Number, default: 200 }
})
const displayRows = computed(() => props.rows.slice(0, props.maxRows))
</script>

<style scoped>
.data-table-wrap { overflow: auto; max-height: 100%; }
.empty { color: var(--text-3); text-align: center; padding: 24px 0; font-size: 12px; }
.data-table { width: 100%; border-collapse: collapse; font-size: 13px; }
.data-table th {
  text-align: left; padding: 8px 10px; color: var(--text-3); font-weight: 500; font-size: 12px;
  border-bottom: 1px solid var(--border-default); position: sticky; top: 0;
  background: #fff; white-space: nowrap;
}
.data-table td {
  padding: 8px 10px; border-bottom: 1px solid var(--border-light); color: var(--text-1);
  white-space: nowrap; overflow: hidden; text-overflow: ellipsis; max-width: 180px;
}
.data-table tbody tr:hover { background: var(--bg-page); }
.unit { color: var(--text-3); font-weight: 400; margin-left: 2px; }
.table-footer { padding: 8px 2px; font-size: 11px; color: var(--text-3); }
.truncated { color: var(--warning); }
</style>

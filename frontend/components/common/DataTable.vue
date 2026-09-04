<template>
  <!-- 明细表：caption 表头 + 列排序（点表头轮换 升/降/原序）+ 复制 TSV + 列宽自适应 -->
  <div class="data-table-wrap">
    <div v-if="!columns.length" class="empty">暂无数据</div>
    <template v-else>
      <div class="table-toolbar">
        <span class="sort-hint">点击列头排序</span>
        <button class="copy-btn" type="button" :class="{ copied }" @click="copyTsv">{{ copied ? '已复制' : '复制数据' }}</button>
      </div>
      <table class="data-table">
        <thead>
          <tr>
            <th
              v-for="(c, i) in columns"
              :key="i"
              :class="{ sortable: true, sorted: sortCol === i }"
              @click="cycleSort(i)"
            >
              {{ c.display || c.name }}<span v-if="c.unit" class="unit">({{ c.unit }})</span>
              <span v-if="sortCol === i" class="sort-arrow">{{ sortDir === 1 ? '↑' : '↓' }}</span>
            </th>
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

// 列排序：sortCol 列下标，sortDir 1 升 / -1 降；再点同列回到原序（-1 之后再点清除）
const sortCol = ref(-1)
const sortDir = ref(1)
watch(() => props.columns, () => { sortCol.value = -1; sortDir.value = 1 })

function cycleSort(i) {
  if (sortCol.value !== i) {
    sortCol.value = i
    sortDir.value = 1
  } else if (sortDir.value === 1) {
    sortDir.value = -1
  } else {
    sortCol.value = -1
  }
}

// 数值感知比较：数字按值比，其余按字符串比
function cellCompare(a, b) {
  const na = parseFloat(a)
  const nb = parseFloat(b)
  if (!isNaN(na) && !isNaN(nb) && String(a).trim() !== '' && String(b).trim() !== '') {
    return (na - nb) * sortDir.value
  }
  return String(a == null ? '' : a).localeCompare(String(b == null ? '' : b), 'zh-CN') * sortDir.value
}

const displayRows = computed(() => {
  let list = props.rows.slice(0, props.maxRows)
  if (sortCol.value >= 0 && sortCol.value < props.columns.length) {
    const i = sortCol.value
    list = list.slice().sort((ra, rb) => cellCompare(ra[i], rb[i]))
  }
  return list
})

// 复制整表为 TSV（粘贴到 Excel/WPS 直接成表）
const copied = ref(false)
function copyTsv() {
  const cols = props.columns.map(c => (c.display || c.name) + (c.unit ? '(' + c.unit + ')' : ''))
  const lines = [cols.join('\t')]
  for (const row of displayRows.value) {
    lines.push(row.map(cell => (cell == null ? '' : String(cell))).join('\t'))
  }
  const text = lines.join('\n')
  if (navigator.clipboard && navigator.clipboard.writeText) {
    navigator.clipboard.writeText(text).then(() => {
      copied.value = true
      setTimeout(() => { copied.value = false }, 1500)
    }).catch(() => {})
  }
}
</script>

<style scoped>
.data-table-wrap { overflow: auto; max-height: 100%; }
.empty { color: var(--text-3); text-align: center; padding: 24px 0; font-size: 12px; }
.table-toolbar {
  display: flex; align-items: center; justify-content: flex-end; gap: 10px;
  padding: 0 2px 6px;
}
.sort-hint { font-size: 11px; color: var(--text-3); }
.copy-btn {
  height: 22px; padding: 0 10px; border-radius: 999px;
  border: 1px solid var(--border-default); background: #fff; color: var(--text-2);
  font: inherit; font-size: 11px; cursor: pointer;
}
.copy-btn:hover { border-color: var(--green-deep); color: var(--green-ink); }
.copy-btn.copied { color: var(--green-ink); border-color: var(--green-deep); }
.data-table { width: 100%; border-collapse: collapse; font-size: 13px; table-layout: auto; }
.data-table th {
  text-align: left; padding: 8px 10px; color: var(--text-3); font-weight: 500; font-size: 12px;
  border-bottom: 1px solid var(--border-default); position: sticky; top: 0;
  background: #fff; white-space: nowrap; cursor: pointer; user-select: none;
}
.data-table th:hover { color: var(--text-1); }
.data-table th.sorted { color: var(--green-ink); font-weight: 600; }
.sort-arrow { margin-left: 3px; font-size: 11px; }
.data-table td {
  padding: 8px 10px; border-bottom: 1px solid var(--border-light); color: var(--text-1);
  white-space: nowrap; overflow: hidden; text-overflow: ellipsis; max-width: 320px;
}
/* 列少时自动撑满（列宽自适应：无横向滚动） */
.data-table td:first-child { max-width: none; }
.data-table tbody tr:hover { background: var(--bg-page); }
.unit { color: var(--text-3); font-weight: 400; margin-left: 2px; }
.table-footer { padding: 8px 2px; font-size: 11px; color: var(--text-3); }
.truncated { color: var(--warning); }
</style>

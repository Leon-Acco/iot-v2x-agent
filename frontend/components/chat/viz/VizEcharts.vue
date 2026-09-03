<template>
  <ChartPanel :option="option" height="300px" />
</template>

<script setup>
defineProps({
  option: { type: Object, required: true }
})
</script>

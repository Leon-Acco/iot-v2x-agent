<template>
  <!-- info tip: question-mark badge with hover tooltip (data source) -->
  <span class="info-tip" tabindex="0" :aria-label="text">?
    <span class="tip-bubble" role="tooltip">{{ text }}</span>
  </span>
</template>

<script setup>
defineProps({
  text: { type: String, default: '' }
})
</script>

<style scoped>
.info-tip {
  position: relative; display: inline-grid; place-items: center;
  width: 14px; height: 14px; margin-left: 5px; border-radius: 50%;
  border: 1px solid #B9C9C2; color: #8C9994; font-size: 10px; line-height: 1;
  cursor: help; vertical-align: 1px; user-select: none;
}
.tip-bubble {
  position: absolute; left: 50%; bottom: calc(100% + 8px); z-index: 30;
  transform: translateX(-50%) translateY(4px);
  width: max-content; max-width: 250px; padding: 8px 11px; border-radius: 8px;
  background: #16302A; color: #EAF6F1; font-size: 11px; line-height: 1.7;
  white-space: normal; text-align: left; letter-spacing: 0; font-weight: 400;
  opacity: 0; pointer-events: none; transition: opacity .15s, transform .15s;
  box-shadow: 0 6px 18px rgba(16, 60, 48, .20);
}
.tip-bubble::after {
  content: ""; position: absolute; left: 50%; top: 100%;
  transform: translateX(-50%); border: 5px solid transparent; border-top-color: #16302A;
}
.info-tip:hover .tip-bubble, .info-tip:focus .tip-bubble {
  opacity: 1; transform: translateX(-50%) translateY(0);
}
</style>

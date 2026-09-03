<template>
  <div></div>
</template>

<script setup>
// 首页中转：已登录进运营工作台，否则进登录页
const { user, me } = useAuth()
onMounted(async () => {
  if (!user.value) await me()
  navigateTo(user.value ? '/chat' : '/login')
})
</script>

<template>
  <div></div>
</template>

<script setup>
// 首页中转：已登录进设备孪生地图，否则进登录页
const { user, me } = useAuth()
onMounted(async () => {
  if (!user.value) await me()
  navigateTo(user.value ? '/map' : '/login')
})
</script>

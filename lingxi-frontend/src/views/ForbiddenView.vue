<script setup lang="ts">
import { RouterLink } from 'vue-router'
</script>

<template>
  <div class="container container--narrow status-page">
    <div class="card status-card">
      <p class="status-code">403</p>
      <h1 class="status-title">无权访问</h1>
      <p class="status-text">
        该页面需要管理员权限。权限判定在服务端，刷新令牌也换不来权限。
      </p>
      <div class="row">
        <RouterLink to="/" class="btn btn--sm">回到广场</RouterLink>
        <RouterLink to="/me" class="btn btn--ghost btn--sm">个人中心</RouterLink>
      </div>
    </div>
  </div>
</template>

<style scoped>
.status-page {
  padding-top: 80px;
}
.status-card {
  padding: 40px 28px;
  text-align: center;
}
.status-code {
  font-size: 44px;
  font-weight: 800;
  color: var(--primary);
  line-height: 1;
}
.status-title {
  font-size: 18px;
  font-weight: 700;
  margin-top: 12px;
}
.status-text {
  color: var(--text-muted);
  font-size: 13px;
  margin: 8px 0 20px;
}
.row {
  justify-content: center;
}
</style>

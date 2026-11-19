<script setup lang="ts">
import { RouterLink } from 'vue-router'
</script>

<template>
  <div class="container container--narrow status-page">
    <div class="card status-card">
      <p class="status-code">404</p>
      <h1 class="status-title">页面不存在</h1>
      <p class="status-text">链接可能已失效，或者这条动态已被删除。</p>
      <RouterLink to="/" class="btn btn--sm">回到广场</RouterLink>
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
</style>

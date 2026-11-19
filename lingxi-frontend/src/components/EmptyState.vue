<script setup lang="ts">
withDefaults(
  defineProps<{
    icon?: string
    title?: string
    message?: string
  }>(),
  { icon: '🌱', title: '暂无内容', message: '' },
)
</script>

<template>
  <div class="empty">
    <div class="empty__icon">{{ icon }}</div>
    <p class="empty__title">{{ title }}</p>
    <p v-if="message" class="empty__message">{{ message }}</p>
    <slot />
  </div>
</template>

<style scoped>
.empty {
  text-align: center;
  padding: 56px 24px;
  color: var(--text-muted);
}
.empty__icon {
  font-size: 36px;
  margin-bottom: 12px;
}
.empty__title {
  font-weight: 600;
  color: var(--text-secondary);
  margin-bottom: 6px;
}
.empty__message {
  font-size: 13px;
}
</style>

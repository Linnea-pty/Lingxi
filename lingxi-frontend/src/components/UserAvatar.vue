<script setup lang="ts">
import { computed } from 'vue'
import { assetUrl, initialOf } from '@/utils/format'

const props = withDefaults(
  defineProps<{
    src?: string
    name?: string
    size?: number
    clickable?: boolean
  }>(),
  { src: '', name: '', size: 40, clickable: false },
)

const imageUrl = computed(() => assetUrl(props.src))
const initial = computed(() => initialOf(props.name))
</script>

<template>
  <span
    class="avatar"
    :class="{ 'avatar--clickable': clickable }"
    :style="{ width: `${size}px`, height: `${size}px`, fontSize: `${Math.round(size * 0.42)}px` }"
  >
    <img v-if="imageUrl" :src="imageUrl" :alt="name || '头像'" loading="lazy" />
    <span v-else class="avatar__fallback">{{ initial }}</span>
  </span>
</template>

<style scoped>
.avatar {
  display: inline-flex;
  align-items: center;
  justify-content: center;
  flex: 0 0 auto;
  border-radius: 50%;
  overflow: hidden;
  background: linear-gradient(135deg, #6366f1, #a855f7);
  color: #fff;
  font-weight: 600;
  user-select: none;
}
.avatar img {
  width: 100%;
  height: 100%;
  object-fit: cover;
}
.avatar--clickable {
  cursor: pointer;
}
.avatar__fallback {
  line-height: 1;
}
</style>

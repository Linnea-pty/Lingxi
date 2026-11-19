<script setup lang="ts">
import { onBeforeUnmount, onMounted, ref, watch } from 'vue'

const props = defineProps<{
  hasMore: boolean
  loading: boolean
  error?: string
}>()

const emit = defineEmits<{ load: [] }>()

const sentinel = ref<HTMLElement | null>(null)
let observer: IntersectionObserver | null = null

function trigger(): void {
  if (props.hasMore && !props.loading) emit('load')
}

onMounted(() => {
  const el = sentinel.value
  if (!el) return
  observer = new IntersectionObserver((entries) => {
    if (entries.some((entry) => entry.isIntersecting)) trigger()
  })
  observer.observe(el)
})

onBeforeUnmount(() => {
  observer?.disconnect()
  observer = null
})

// 末页之后不再观察，避免空转；刷新后又出现更多数据时要重新挂上观察器
watch(
  () => props.hasMore,
  (hasMore) => {
    if (!hasMore) {
      observer?.disconnect()
      return
    }
    const el = sentinel.value
    if (el) observer?.observe(el)
  },
)
</script>

<template>
  <div ref="sentinel" class="load-more">
    <p v-if="error" class="load-more__error">{{ error }}</p>
    <span v-else-if="loading" class="load-more__hint">加载中…</span>
    <button v-else-if="hasMore" type="button" class="btn btn--ghost" @click="emit('load')">
      加载更多
    </button>
    <span v-else class="load-more__hint">没有更多了</span>
  </div>
</template>

<style scoped>
.load-more {
  display: flex;
  justify-content: center;
  padding: 20px 0 8px;
  min-height: 48px;
  align-items: center;
}
.load-more__hint {
  color: var(--text-muted);
  font-size: 13px;
}
.load-more__error {
  color: var(--danger);
  font-size: 13px;
}
</style>

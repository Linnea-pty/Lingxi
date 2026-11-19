<script setup lang="ts">
import { computed, onMounted, ref, watch } from 'vue'
import PostCard from '@/components/PostCard.vue'
import LoadMore from '@/components/LoadMore.vue'
import EmptyState from '@/components/EmptyState.vue'
import { postApi } from '@/api/post'
import { describeError } from '@/api/http'
import { useCursorList } from '@/composables/useCursorList'
import { useAuthStore } from '@/stores/auth'
import { newRequestId } from '@/utils/requestId'
import type { PostVO } from '@/api/types'

const auth = useAuthStore()

const feed = useCursorList<PostVO>(async (cursor, size) => {
  const page = await postApi.feed(cursor, size)
  return { items: page.postList, nextCursor: page.nextCursor, hasMore: page.hasMore }
})

const content = ref('')
const publishing = ref(false)
const publishError = ref('')

// 一次点击生成一个幂等键，失败重试复用同一个值
let pendingRequestId: string | null = null

const trimmed = computed(() => content.value.trim())
const canPublish = computed(() => trimmed.value.length > 0 && trimmed.value.length <= 2000)

async function publish(): Promise<void> {
  if (!canPublish.value || publishing.value) return
  publishing.value = true
  publishError.value = ''
  const requestId = pendingRequestId ?? newRequestId()
  pendingRequestId = requestId
  try {
    const created = await postApi.create({ content: trimmed.value, requestId })
    pendingRequestId = null
    content.value = ''
    // 新动态直接插入列表头，避免整页重拉
    feed.items.value = [created, ...feed.items.value]
  } catch (error) {
    publishError.value = describeError(error)
  } finally {
    publishing.value = false
  }
}

function handleUpdated(updated: PostVO): void {
  feed.update(
    (post) => post.postId === updated.postId,
    (post) => ({ ...post, ...updated }),
  )
}

function handleDeleted(postId: number): void {
  feed.update(
    (post) => post.postId === postId,
    () => null,
  )
}

watch(
  () => auth.user?.userId,
  (userId, previous) => {
    if (previous !== undefined && userId !== previous) feed.loadFirst()
  },
)

onMounted(feed.loadFirst)
</script>

<template>
  <div class="container">
    <section class="composer card">
      <div class="composer__head">
        <h1 class="page-title">说点什么</h1>
        <span class="muted text-sm">{{ trimmed.length }}/2000</span>
      </div>
      <textarea
        v-model="content"
        class="textarea"
        rows="3"
        maxlength="2000"
        placeholder="此刻的想法…"
        @keydown.ctrl.enter.prevent="publish"
      />
      <div class="composer__foot">
        <span class="muted text-sm">Ctrl / ⌘ + Enter 快速发布</span>
        <button class="btn" type="button" :disabled="!canPublish || publishing" @click="publish">
          {{ publishing ? '发布中…' : '发布' }}
        </button>
      </div>
      <p v-if="publishError" class="composer__error">{{ publishError }}</p>
    </section>

    <section class="feed-head">
      <div>
        <h2 class="page-title">动态广场</h2>
        <p class="page-subtitle">置顶优先，其余按发布时间倒序</p>
      </div>
      <button class="btn btn--ghost btn--sm" type="button" :disabled="feed.loading.value" @click="feed.loadFirst()">
        刷新
      </button>
    </section>

    <div v-if="feed.loading.value" class="center">
      <span class="spinner" />
    </div>

    <div v-else-if="feed.error.value" class="alert alert--error">
      {{ feed.error.value }}
      <button class="btn btn--ghost btn--sm" type="button" @click="feed.loadFirst()">重试</button>
    </div>

    <EmptyState
      v-else-if="feed.items.value.length === 0"
      icon="✍️"
      title="还没有动态"
      message="发出第一条动态，让大家认识你"
    />

    <div v-else class="stack">
      <PostCard
        v-for="post in feed.items.value"
        :key="post.postId"
        :post="post"
        :admin-mode="auth.isAdmin"
        @updated="handleUpdated"
        @deleted="handleDeleted"
      />
    </div>

    <LoadMore
      v-if="feed.items.value.length > 0"
      :has-more="feed.hasMore.value"
      :loading="feed.loadingMore.value"
      :error="feed.error.value"
      @load="feed.loadMore()"
    />
  </div>
</template>

<style scoped>
.composer {
  padding: 20px;
  margin-bottom: 24px;
}
.composer__head {
  display: flex;
  justify-content: space-between;
  align-items: baseline;
  margin-bottom: 10px;
}
.composer__foot {
  display: flex;
  justify-content: space-between;
  align-items: center;
  margin-top: 12px;
}
.composer__error {
  color: var(--danger);
  font-size: 12px;
  margin-top: 8px;
}
.feed-head {
  display: flex;
  justify-content: space-between;
  align-items: flex-start;
  margin-bottom: 14px;
}
</style>

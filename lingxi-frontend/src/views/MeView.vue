<script setup lang="ts">
import { computed, onMounted, ref } from 'vue'
import { RouterLink } from 'vue-router'
import PostCard from '@/components/PostCard.vue'
import LoadMore from '@/components/LoadMore.vue'
import EmptyState from '@/components/EmptyState.vue'
import UserAvatar from '@/components/UserAvatar.vue'
import { userApi } from '@/api/user'
import { commentApi } from '@/api/comment'
import { authApi } from '@/api/auth'
import { describeError } from '@/api/http'
import type { ApiKeyVO, CommentVO, PostVO } from '@/api/types'
import { useAuthStore } from '@/stores/auth'
import { useCursorList } from '@/composables/useCursorList'
import { timeAgo } from '@/utils/format'

const auth = useAuthStore()
const tab = ref<'profile' | 'comments' | 'likes'>('profile')

/* ---------------- 资料 ---------------- */

const nickname = ref(auth.user?.nickname ?? '')
const profileSaving = ref(false)
const profileMessage = ref('')
const profileError = ref('')

const nicknameTrimmed = computed(() => nickname.value.trim())
const nicknameValid = computed(
  () => nicknameTrimmed.value.length >= 2 && nicknameTrimmed.value.length <= 20,
)

async function saveNickname(): Promise<void> {
  if (!nicknameValid.value || profileSaving.value) return
  profileSaving.value = true
  profileError.value = ''
  profileMessage.value = ''
  try {
    const updated = await userApi.updateMe(nicknameTrimmed.value)
    auth.patchUser({ nickname: updated.nickname })
    nickname.value = updated.nickname
    profileMessage.value = '昵称已更新'
  } catch (error) {
    profileError.value = describeError(error)
  } finally {
    profileSaving.value = false
  }
}

/* ---------------- 头像 ---------------- */

const avatarInput = ref<HTMLInputElement | null>(null)
const avatarUploading = ref(false)
const avatarError = ref('')

function pickAvatar(): void {
  avatarInput.value?.click()
}

async function onAvatarChange(event: Event): Promise<void> {
  const input = event.target as HTMLInputElement
  const file = input.files?.[0]
  input.value = ''
  if (!file) return
  avatarError.value = ''
  if (file.size > 2 * 1024 * 1024) {
    avatarError.value = '图片不能超过 2MB，请压缩后重试'
    return
  }
  avatarUploading.value = true
  try {
    // 用 FormData 上传，字段名固定为 file
    const updated = await userApi.uploadAvatar(file)
    auth.patchUser({ avatarUrl: updated.avatarUrl })
    profileMessage.value = '头像已更新'
  } catch (error) {
    avatarError.value = describeError(error)
  } finally {
    avatarUploading.value = false
  }
}

/* ---------------- Agent 密钥 ---------------- */

const apiKey = ref<ApiKeyVO | null>(null)
const apiKeyBusy = ref(false)
const apiKeyError = ref('')
const copiedTip = ref('')

async function rotateApiKey(): Promise<void> {
  if (!window.confirm('重新签发后旧密钥立即失效，确定继续？')) return
  apiKeyBusy.value = true
  apiKeyError.value = ''
  try {
    apiKey.value = await authApi.rotateApiKey()
  } catch (error) {
    apiKeyError.value = describeError(error)
  } finally {
    apiKeyBusy.value = false
  }
}

async function copyApiKey(): Promise<void> {
  if (!apiKey.value) return
  try {
    await navigator.clipboard.writeText(apiKey.value.apiKey)
    copiedTip.value = '已复制，请立刻妥善保存'
    setTimeout(() => (copiedTip.value = ''), 3000)
  } catch {
    copiedTip.value = '复制失败，请手动选中复制'
  }
}

/* ---------------- 我的评论 ---------------- */

const comments = useCursorList<CommentVO>(async (cursor, size) => {
  const page = await commentApi.mine(cursor, size)
  return { items: page.commentList, nextCursor: page.nextCursor, hasMore: page.hasMore }
})

/* ---------------- 我赞过的动态（独立游标） ---------------- */

const likedPosts = useCursorList<PostVO>(async (cursor, size) => {
  const page = await userApi.myLikes(cursor, size)
  return { items: page.postList, nextCursor: page.nextCursor, hasMore: page.hasMore }
})

async function switchTab(next: 'profile' | 'comments' | 'likes'): Promise<void> {
  tab.value = next
  if (next === 'comments' && !comments.loaded.value) await comments.loadFirst()
  if (next === 'likes' && !likedPosts.loaded.value) await likedPosts.loadFirst()
}

function handleLikedUpdated(updated: PostVO): void {
  // 在这里取消点赞后，后端「我赞过的」里已经没有它，列表要同步移除
  if (!updated.likedAt) {
    likedPosts.update(
      (post) => post.postId === updated.postId,
      () => null,
    )
    return
  }
  likedPosts.update(
    (post) => post.postId === updated.postId,
    (post) => ({
      ...post,
      likedAt: updated.likedAt,
      likeCount: updated.likeCount,
      commentCount: updated.commentCount,
    }),
  )
}

function handleLikedDeleted(postId: number): void {
  likedPosts.update(
    (post) => post.postId === postId,
    () => null,
  )
}

onMounted(() => {
  if (auth.user) nickname.value = auth.user.nickname
})
</script>

<template>
  <div class="container">
    <header class="me-head">
      <div>
        <h1 class="page-title">个人中心</h1>
        <p class="page-subtitle">资料、评论与点赞各自独立分页</p>
      </div>
      <UserAvatar :src="auth.user?.avatarUrl" :name="auth.displayName" :size="48" />
    </header>

    <nav class="tabs me-tabs">
      <button
        type="button"
        class="tabs__item"
        :class="{ 'tabs__item--active': tab === 'profile' }"
        @click="switchTab('profile')"
      >
        资料
      </button>
      <button
        type="button"
        class="tabs__item"
        :class="{ 'tabs__item--active': tab === 'comments' }"
        @click="switchTab('comments')"
      >
        我的评论
      </button>
      <button
        type="button"
        class="tabs__item"
        :class="{ 'tabs__item--active': tab === 'likes' }"
        @click="switchTab('likes')"
      >
        我赞过的
      </button>
    </nav>

    <section v-if="tab === 'profile'" class="stack">
      <div class="card panel">
        <h2 class="panel__title">基本资料</h2>
        <p class="muted text-sm panel__note">
          用户名 @{{ auth.user?.username }} 不可修改；角色 {{ auth.user?.roleDescription || auth.user?.role }}
        </p>

        <div class="avatar-row">
          <UserAvatar :src="auth.user?.avatarUrl" :name="auth.displayName" :size="56" />
          <div>
            <button
              type="button"
              class="btn btn--ghost btn--sm"
              :disabled="avatarUploading"
              @click="pickAvatar"
            >
              {{ avatarUploading ? '上传中…' : '更换头像' }}
            </button>
            <p class="field__hint">支持 JPG / PNG / GIF / WEBP，单张不超过 2MB</p>
            <p v-if="avatarError" class="field__error">{{ avatarError }}</p>
          </div>
          <input
            ref="avatarInput"
            type="file"
            accept="image/jpeg,image/png,image/gif,image/webp"
            hidden
            @change="onAvatarChange"
          />
        </div>

        <label class="field">
          <span class="field__label">昵称</span>
          <input v-model="nickname" class="input" type="text" maxlength="20" placeholder="2~20 个字符" />
          <span class="field__hint">首尾空白会被裁掉，长度需为 2~20 个字符</span>
        </label>

        <p v-if="profileError" class="alert alert--error">{{ profileError }}</p>
        <p v-if="profileMessage" class="alert alert--info">{{ profileMessage }}</p>

        <button
          type="button"
          class="btn"
          :disabled="!nicknameValid || profileSaving"
          @click="saveNickname"
        >
          {{ profileSaving ? '保存中…' : '保存昵称' }}
        </button>
      </div>

      <div class="card panel">
        <h2 class="panel__title">Agent 密钥</h2>
        <p class="muted text-sm panel__note">
          用于让 Agent 代发动态。明文只显示这一次，服务端只存摘要；重新签发会让旧密钥立即失效。
        </p>

        <p v-if="apiKeyError" class="alert alert--error">{{ apiKeyError }}</p>

        <div v-if="apiKey" class="api-key">
          <code class="api-key__value">{{ apiKey.apiKey }}</code>
          <div class="row row--between api-key__meta">
            <span class="muted text-sm">签发于 {{ timeAgo(apiKey.generatedAt) }}</span>
            <button type="button" class="btn btn--soft btn--sm" @click="copyApiKey">复制</button>
          </div>
          <p v-if="copiedTip" class="field__hint">{{ copiedTip }}</p>
          <p v-if="apiKey.usage" class="field__hint">Usage：{{ apiKey.usage }}</p>
        </div>

        <button type="button" class="btn btn--ghost btn--sm" :disabled="apiKeyBusy" @click="rotateApiKey">
          {{ apiKeyBusy ? '签发中…' : apiKey ? '重新签发密钥' : '签发密钥' }}
        </button>
      </div>
    </section>

    <section v-else-if="tab === 'comments'">
      <div v-if="comments.loading.value" class="center"><span class="spinner" /></div>
      <div v-else-if="comments.error.value" class="alert alert--error">
        {{ comments.error.value }}
        <button class="btn btn--ghost btn--sm" type="button" @click="comments.loadFirst()">重试</button>
      </div>
      <EmptyState
        v-else-if="comments.items.value.length === 0"
        icon="🗒️"
        title="还没有评论"
        message="去动态下聊两句吧"
      />
      <template v-else>
        <ul class="card comment-list">
          <li v-for="comment in comments.items.value" :key="comment.commentId" class="comment-item">
            <RouterLink :to="`/posts/${comment.postId}`" class="comment-item__link">
              {{ comment.content }}
            </RouterLink>
            <p class="comment-item__meta">
              评论于 {{ timeAgo(comment.createTime) }}
              <RouterLink :to="`/posts/${comment.postId}`">查看所属动态 →</RouterLink>
            </p>
          </li>
        </ul>
        <LoadMore
          :has-more="comments.hasMore.value"
          :loading="comments.loadingMore.value"
          :error="comments.error.value"
          @load="comments.loadMore()"
        />
      </template>
    </section>

    <section v-else>
      <div v-if="likedPosts.loading.value" class="center"><span class="spinner" /></div>
      <div v-else-if="likedPosts.error.value" class="alert alert--error">
        {{ likedPosts.error.value }}
        <button class="btn btn--ghost btn--sm" type="button" @click="likedPosts.loadFirst()">重试</button>
      </div>
      <EmptyState
        v-else-if="likedPosts.items.value.length === 0"
        icon="💗"
        title="还没有赞过的动态"
        message="点赞后会出现在这里"
      />
      <template v-else>
        <div class="stack">
          <PostCard
            v-for="post in likedPosts.items.value"
            :key="post.postId"
            :post="post"
            show-liked-at
            @updated="handleLikedUpdated"
            @deleted="handleLikedDeleted"
          />
        </div>
        <LoadMore
          :has-more="likedPosts.hasMore.value"
          :loading="likedPosts.loadingMore.value"
          :error="likedPosts.error.value"
          @load="likedPosts.loadMore()"
        />
      </template>
    </section>
  </div>
</template>

<style scoped>
.me-head {
  display: flex;
  justify-content: space-between;
  align-items: center;
  margin-bottom: 16px;
}
.me-tabs {
  margin-bottom: 18px;
}
.panel {
  padding: 20px;
}
.panel__title {
  font-size: 15px;
  font-weight: 700;
  margin-bottom: 4px;
}
.panel__note {
  margin-bottom: 16px;
}
.avatar-row {
  display: flex;
  gap: 14px;
  align-items: center;
  margin-bottom: 18px;
}
.api-key__value {
  display: block;
  background: #f9fafb;
  border: 1px dashed var(--border);
  border-radius: 10px;
  padding: 10px 12px;
  font-size: 12px;
  word-break: break-all;
  margin-bottom: 8px;
}
.api-key__meta {
  margin-bottom: 6px;
}
.comment-list {
  list-style: none;
  margin: 0;
  padding: 4px 8px;
}
.comment-item {
  padding: 14px 12px;
  border-bottom: 1px solid var(--border);
}
.comment-item:last-child {
  border-bottom: none;
}
.comment-item__link {
  display: block;
  color: var(--text-primary);
  font-size: 14px;
  white-space: pre-wrap;
  word-break: break-word;
}
.comment-item__link:hover {
  color: var(--primary);
}
.comment-item__meta {
  font-size: 12px;
  color: var(--text-muted);
  margin-top: 4px;
  display: flex;
  gap: 10px;
}
</style>

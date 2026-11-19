<script setup lang="ts">
import { computed, ref, watch } from 'vue'
import { RouterLink } from 'vue-router'
import UserAvatar from './UserAvatar.vue'
import { postApi } from '@/api/post'
import { adminApi } from '@/api/admin'
import { describeError } from '@/api/http'
import type { PostVO } from '@/api/types'
import { useAuthStore } from '@/stores/auth'
import { timeAgo } from '@/utils/format'

const props = withDefaults(
  defineProps<{
    post: PostVO
    /** 后台模式：可删除任意动态（作者仍可编辑自己的正文） */
    adminMode?: boolean
    /** 是否显示「在我赞过的列表里」的点赞时间 */
    showLikedAt?: boolean
  }>(),
  { adminMode: false, showLikedAt: false },
)

const emit = defineEmits<{
  updated: [post: PostVO]
  deleted: [postId: number]
}>()

const auth = useAuthStore()

const local = ref<PostVO>({ ...props.post })
const liked = ref<boolean>(Boolean(props.post.likedAt))
const busy = ref(false)
const editing = ref(false)
const draft = ref(props.post.content)
const actionError = ref('')

watch(
  () => props.post,
  (next) => {
    local.value = { ...next }
    if (!editing.value) draft.value = next.content
  },
)

const isOwner = computed(() => auth.user?.userId === local.value.authorId)
const canEdit = computed(() => isOwner.value)
const canDelete = computed(() => props.adminMode || isOwner.value)
const contentTooLong = computed(() => draft.value.trim().length > 2000)
// 详情页没有单条动态查询接口，靠 history.state 把整条动态带过去。
// WHY 要复制成普通对象：local 是响应式代理，history.pushState 用结构化克隆序列化，
// 遇到 Proxy 会抛 DataCloneError，vue-router 兜底成 location.assign 造成整页刷新。
const detailLink = computed(() => ({
  path: `/posts/${local.value.postId}`,
  state: { post: { ...local.value } },
}))

async function toggleLike(): Promise<void> {
  if (busy.value) return
  busy.value = true
  actionError.value = ''
  try {
    // 服务端返回操作后的状态与最新总数，直接覆盖本地，不做自增自减
    const result = liked.value
      ? await postApi.unlike(local.value.postId)
      : await postApi.like(local.value.postId)
    liked.value = result.liked
    // 同步 likedAt：列表页要靠它判断这条动态是否还该留在「我赞过的」里
    local.value = {
      ...local.value,
      likeCount: result.likeCount,
      likedAt: result.liked ? new Date().toISOString() : null,
    }
    emit('updated', local.value)
  } catch (error) {
    actionError.value = describeError(error)
  } finally {
    busy.value = false
  }
}

async function saveEdit(): Promise<void> {
  const content = draft.value.trim()
  if (!content || contentTooLong.value) return
  busy.value = true
  actionError.value = ''
  try {
    const updated = await postApi.update(local.value.postId, content)
    local.value = { ...updated, likedAt: local.value.likedAt }
    editing.value = false
    emit('updated', local.value)
  } catch (error) {
    actionError.value = describeError(error)
  } finally {
    busy.value = false
  }
}

async function removePost(): Promise<void> {
  if (!window.confirm('删除后动态与其下全部评论都会消失，确定删除？')) return
  busy.value = true
  actionError.value = ''
  try {
    if (props.adminMode) await adminApi.removePost(local.value.postId)
    else await postApi.remove(local.value.postId)
    emit('deleted', local.value.postId)
  } catch (error) {
    actionError.value = describeError(error)
    busy.value = false
  }
}

function startEdit(): void {
  draft.value = local.value.content
  editing.value = true
}
</script>

<template>
  <article class="post-card card">
    <header class="post-card__head">
      <RouterLink :to="`/users/${local.authorId}`" class="post-card__author">
        <UserAvatar :src="local.authorAvatarUrl" :name="local.authorNickname" :size="40" />
        <span class="post-card__author-text">
          <span class="post-card__nickname">{{ local.authorNickname }}</span>
          <span class="post-card__time">{{ timeAgo(local.createTime) }}</span>
        </span>
      </RouterLink>
      <div class="post-card__tags">
        <span v-if="local.isPinned" class="tag tag--pinned">置顶</span>
        <span v-if="local.source === 'agent'" class="tag tag--agent">Agent</span>
      </div>
    </header>

    <div v-if="editing" class="post-card__editor">
      <textarea v-model="draft" class="textarea" rows="4" maxlength="2000" />
      <div class="post-card__editor-actions">
        <span class="muted text-sm">{{ draft.trim().length }}/2000</span>
        <div class="row">
          <button class="btn btn--ghost btn--sm" type="button" @click="editing = false">取消</button>
          <button
            class="btn btn--sm"
            type="button"
            :disabled="busy || !draft.trim() || contentTooLong"
            @click="saveEdit"
          >
            保存
          </button>
        </div>
      </div>
    </div>
    <p v-else class="post-card__content">{{ local.content }}</p>

    <p v-if="actionError" class="post-card__error">{{ actionError }}</p>

    <footer class="post-card__actions">
      <button
        type="button"
        class="action"
        :class="{ 'action--active': liked }"
        :disabled="busy"
        @click="toggleLike"
      >
        <span aria-hidden="true">{{ liked ? '♥' : '♡' }}</span>
        {{ local.likeCount }}
      </button>
      <RouterLink :to="detailLink" class="action">
        <span aria-hidden="true">💬</span>
        {{ local.commentCount }}
      </RouterLink>
      <span v-if="showLikedAt && local.likedAt" class="muted text-sm">
        赞于 {{ timeAgo(local.likedAt) }}
      </span>
      <span class="post-card__spacer" />
      <button v-if="canEdit" type="button" class="link-btn" @click="startEdit">编辑</button>
      <button v-if="canDelete" type="button" class="link-btn link-btn--danger" @click="removePost">
        删除
      </button>
    </footer>
  </article>
</template>

<style scoped>
.post-card {
  padding: 18px 20px;
}
.post-card__head {
  display: flex;
  justify-content: space-between;
  align-items: flex-start;
  gap: 12px;
  margin-bottom: 12px;
}
.post-card__author {
  display: flex;
  gap: 10px;
  align-items: center;
  color: inherit;
  min-width: 0;
}
.post-card__author:hover .post-card__nickname {
  color: var(--primary);
}
.post-card__author-text {
  display: flex;
  flex-direction: column;
  min-width: 0;
}
.post-card__nickname {
  font-weight: 600;
  font-size: 14px;
  color: var(--text-primary);
  overflow: hidden;
  text-overflow: ellipsis;
  white-space: nowrap;
}
.post-card__time {
  font-size: 12px;
  color: var(--text-muted);
}
.post-card__tags {
  display: flex;
  gap: 6px;
  flex: 0 0 auto;
}
.post-card__content {
  white-space: pre-wrap;
  word-break: break-word;
  color: var(--text-primary);
  font-size: 15px;
}
.post-card__editor {
  margin-bottom: 6px;
}
.post-card__editor-actions {
  display: flex;
  justify-content: space-between;
  align-items: center;
  margin-top: 8px;
}
.post-card__error {
  color: var(--danger);
  font-size: 12px;
  margin-top: 8px;
}
.post-card__actions {
  display: flex;
  align-items: center;
  gap: 14px;
  margin-top: 14px;
  padding-top: 12px;
  border-top: 1px solid var(--border);
}
.post-card__spacer {
  flex: 1;
}
.action {
  display: inline-flex;
  align-items: center;
  gap: 5px;
  border: none;
  background: transparent;
  color: var(--text-secondary);
  font-size: 13px;
  font-weight: 500;
  cursor: pointer;
  padding: 2px 4px;
  border-radius: 6px;
}
.action:hover {
  color: var(--primary);
  background: var(--primary-soft);
}
.action:disabled {
  cursor: default;
  opacity: 0.6;
}
.action--active {
  color: #e11d48;
}
.action--active:hover {
  color: #be123c;
}
.link-btn {
  border: none;
  background: transparent;
  color: var(--text-muted);
  font-size: 13px;
  cursor: pointer;
  padding: 2px 4px;
}
.link-btn:hover {
  color: var(--primary);
}
.link-btn--danger:hover {
  color: var(--danger);
}
</style>

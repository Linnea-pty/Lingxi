<script setup lang="ts">
import { computed, nextTick, onMounted, reactive, ref } from 'vue'
import { useRoute, useRouter } from 'vue-router'
import PostCard from '@/components/PostCard.vue'
import CommentItem from '@/components/CommentItem.vue'
import LoadMore from '@/components/LoadMore.vue'
import EmptyState from '@/components/EmptyState.vue'
import UserAvatar from '@/components/UserAvatar.vue'
import { postApi } from '@/api/post'
import { commentApi } from '@/api/comment'
import { describeError } from '@/api/http'
import type { CommentVO, LikeUserVO, PostVO } from '@/api/types'
import { useCursorList } from '@/composables/useCursorList'
import { useAuthStore } from '@/stores/auth'
import { newRequestId } from '@/utils/requestId'
import { timeAgo } from '@/utils/format'

const route = useRoute()
const router = useRouter()
const auth = useAuthStore()

const postId = computed(() => Number(route.params.postId))

const post = ref<PostVO | null>(null)
const postLoading = ref(true)
const postMissing = ref(false)
/** 动态与评论区共用的加载错误提示 */
const commentError = ref('')

/** 没有单条动态查询接口：优先用列表带来的 state，缺失时回退到信息流里向前翻找 */
async function resolvePost(): Promise<void> {
  postLoading.value = true
  postMissing.value = false
  const state = window.history.state as { post?: PostVO } | null
  if (state?.post && state.post.postId === postId.value) {
    post.value = state.post
    postLoading.value = false
    return
  }
  try {
    let cursor: string | null = null
    for (let page = 0; page < 10; page += 1) {
      const result = await postApi.feed(cursor, 30)
      const found = result.postList.find((item) => item.postId === postId.value)
      if (found) {
        post.value = found
        postLoading.value = false
        return
      }
      if (!result.hasMore || !result.nextCursor) break
      cursor = result.nextCursor
    }
    postMissing.value = true
  } catch (error) {
    commentError.value = describeError(error)
  } finally {
    postLoading.value = false
  }
}

/* ---------------- 一级评论（倒序） ---------------- */

const roots = useCursorList<CommentVO>(async (cursor, size) => {
  const page = await commentApi.listRoots(postId.value, cursor, size)
  return { items: page.commentList, nextCursor: page.nextCursor, hasMore: page.hasMore }
})

/* ---------------- 讨论串（正序） ---------------- */

interface Thread {
  items: CommentVO[]
  cursor: string | null
  hasMore: boolean
  loading: boolean
  loaded: boolean
  error: string
}

const threads = reactive<Record<number, Thread>>({})

function ensureThread(rootId: number): Thread {
  threads[rootId] ??= {
    items: [],
    cursor: null,
    hasMore: false,
    loading: false,
    loaded: false,
    error: '',
  }
  return threads[rootId] as Thread
}

async function loadReplies(rootId: number, reset = false): Promise<void> {
  const thread = ensureThread(rootId)
  if (thread.loading) return
  if (!reset && (!thread.hasMore || thread.cursor === null)) {
    if (thread.loaded) return
  }
  thread.loading = true
  thread.error = ''
  try {
    const cursor = reset ? null : thread.cursor
    const page = await commentApi.listReplies(rootId, cursor, 10)
    thread.items = reset ? page.commentList : [...thread.items, ...page.commentList]
    thread.cursor = page.nextCursor
    thread.hasMore = page.hasMore
    thread.loaded = true
  } catch (error) {
    thread.error = describeError(error)
  } finally {
    thread.loading = false
  }
}

async function toggleReplies(root: CommentVO): Promise<void> {
  const thread = ensureThread(root.commentId)
  if (thread.loaded) {
    thread.items = []
    thread.cursor = null
    thread.hasMore = false
    thread.loaded = false
    return
  }
  await loadReplies(root.commentId, true)
}

function isExpanded(rootId: number): boolean {
  return Boolean(threads[rootId]?.loaded)
}

/* ---------------- 发表评论 / 回复 ---------------- */

const draft = ref('')
const submitError = ref('')
const submitting = ref(false)
const composerEl = ref<HTMLTextAreaElement | null>(null)
const replyTarget = ref<{ rootId: number; parentId: number; nickname: string } | null>(null)

let pendingRequestId: string | null = null

const canSubmit = computed(() => draft.value.trim().length > 0 && draft.value.trim().length <= 1000)

function startReply(comment: CommentVO): void {
  // 回复的回复也归到同一讨论串，rootId 由父评论继承
  replyTarget.value = {
    rootId: comment.rootId || comment.commentId,
    parentId: comment.commentId,
    nickname: comment.authorNickname,
  }
  nextTick(() => composerEl.value?.focus())
}

function cancelReply(): void {
  replyTarget.value = null
}

async function submitComment(): Promise<void> {
  if (!canSubmit.value || submitting.value) return
  submitting.value = true
  submitError.value = ''
  const requestId = pendingRequestId ?? newRequestId()
  pendingRequestId = requestId
  try {
    const created = await commentApi.create(postId.value, {
      content: draft.value.trim(),
      parentId: replyTarget.value?.parentId ?? 0,
      requestId,
    })
    pendingRequestId = null
    draft.value = ''
    bumpCommentCount(1)

    const target = replyTarget.value
    replyTarget.value = null

    if (!target) {
      // 一级评论按倒序展示，新评论插到最前
      roots.items.value = [created, ...roots.items.value]
      return
    }

    const root = roots.items.value.find((item) => item.commentId === target.rootId)
    if (root) {
      roots.update(
        (item) => item.commentId === target.rootId,
        (item) => ({ ...item, replyCount: (item.replyCount ?? 0) + 1 }),
      )
    }
    // 回复列表按正序，重新拉取该讨论串，避免本地拼接与游标错位
    if (isExpanded(target.rootId)) await loadReplies(target.rootId, true)
  } catch (error) {
    submitError.value = describeError(error)
  } finally {
    submitting.value = false
  }
}

function bumpCommentCount(delta: number): void {
  if (!post.value) return
  post.value = {
    ...post.value,
    commentCount: Math.max(0, post.value.commentCount + delta),
  }
}

function handleRootDeleted(commentId: number): void {
  const root = roots.items.value.find((item) => item.commentId === commentId)
  roots.update(
    (item) => item.commentId === commentId,
    () => null,
  )
  delete threads[commentId]
  // 删根评论会级联删掉其下全部回复
  bumpCommentCount(-(1 + (root?.replyCount ?? 0)))
}

async function handleReplyDeleted(rootId: number, commentId: number): Promise<void> {
  const thread = ensureThread(rootId)
  thread.items = thread.items.filter((item) => item.commentId !== commentId)
  roots.update(
    (item) => item.commentId === rootId,
    (item) => ({ ...item, replyCount: Math.max(0, (item.replyCount ?? 0) - 1) }),
  )
  bumpCommentCount(-1)
}

/* ---------------- 点赞人列表 ---------------- */

const likes = useCursorList<LikeUserVO>(async (cursor, size) => {
  const page = await postApi.likeUsers(postId.value, cursor, size)
  return { items: page.userList, nextCursor: page.nextCursor, hasMore: page.hasMore }
})

const likesOpen = ref(false)

async function toggleLikes(): Promise<void> {
  likesOpen.value = !likesOpen.value
  if (likesOpen.value && !likes.loaded.value) await likes.loadFirst()
}

function handlePostUpdated(updated: PostVO): void {
  post.value = updated
}

function handlePostDeleted(): void {
  router.replace('/')
}

onMounted(async () => {
  await resolvePost()
  // WHY 动态没找到还要拉评论：链接直达或刷新时 state 可能已丢失，正文拿不到，
  // 但评论列表是独立接口，能拉到就仍然可看、可回复。
  await roots.loadFirst()
})
</script>

<template>
  <div class="container">
    <button class="back" type="button" @click="router.back()">← 返回</button>

    <div v-if="postLoading" class="center"><span class="spinner" /></div>

    <template v-else>
      <PostCard
        v-if="post"
        :post="post"
        :admin-mode="auth.isAdmin"
        @updated="handlePostUpdated"
        @deleted="handlePostDeleted"
      />
      <div v-else class="card missing">
        <p class="missing__title">动态内容不可用</p>
        <p class="muted text-sm">该动态可能已被删除，下方评论区仍会尝试加载。</p>
      </div>

      <section class="card likes">
        <button class="likes__toggle" type="button" @click="toggleLikes">
          <span>♡ 点赞的人</span>
          <span class="muted text-sm">{{ likesOpen ? '收起' : '展开' }}</span>
        </button>
        <div v-if="likesOpen" class="likes__body">
          <div v-if="likes.loading.value" class="center"><span class="spinner" /></div>
          <p v-else-if="likes.items.value.length === 0" class="muted text-sm">
            还没有人点赞，来做第一个
          </p>
          <ul v-else class="likes__list">
            <li v-for="user in likes.items.value" :key="user.userId">
              <RouterLink :to="`/users/${user.userId}`" class="likes__user">
                <UserAvatar :src="user.avatarUrl" :name="user.nickname" :size="30" />
                <span>
                  <span class="likes__nickname">{{ user.nickname }}</span>
                  <span class="likes__time">{{ timeAgo(user.likedAt) }}赞过</span>
                </span>
              </RouterLink>
            </li>
          </ul>
          <LoadMore
            v-if="likes.items.value.length > 0"
            :has-more="likes.hasMore.value"
            :loading="likes.loadingMore.value"
            :error="likes.error.value"
            @load="likes.loadMore()"
          />
        </div>
      </section>

      <section class="card comments">
        <h2 class="comments__title">评论</h2>

        <div class="composer">
          <p v-if="replyTarget" class="composer__target">
            回复 <strong>@{{ replyTarget.nickname }}</strong>
            <button type="button" class="link-btn" @click="cancelReply">取消</button>
          </p>
          <textarea
            ref="composerEl"
            v-model="draft"
            class="textarea"
            rows="3"
            maxlength="1000"
            :placeholder="replyTarget ? `回复 @${replyTarget.nickname}…` : '写下你的评论…'"
            @keydown.ctrl.enter.prevent="submitComment"
          />
          <div class="composer__foot">
            <span class="muted text-sm">{{ draft.trim().length }}/1000</span>
            <button
              class="btn btn--sm"
              type="button"
              :disabled="!canSubmit || submitting"
              @click="submitComment"
            >
              {{ submitting ? '发送中…' : replyTarget ? '回复' : '发表评论' }}
            </button>
          </div>
          <p v-if="submitError" class="composer__error">{{ submitError }}</p>
        </div>

        <p v-if="commentError" class="alert alert--error">{{ commentError }}</p>

        <div v-if="roots.loading.value" class="center"><span class="spinner" /></div>

        <div v-else-if="roots.error.value" class="alert alert--error">
          {{ roots.error.value }}
          <button class="btn btn--ghost btn--sm" type="button" @click="roots.loadFirst()">重试</button>
        </div>

        <EmptyState
          v-else-if="roots.items.value.length === 0"
          icon="💬"
          title="还没有评论"
          message="抢个沙发吧"
        />

        <div v-else class="comment-list">
          <CommentItem
            v-for="root in roots.items.value"
            :key="root.commentId"
            :comment="root"
            variant="root"
            :admin-mode="auth.isAdmin"
            @reply="startReply"
            @deleted="handleRootDeleted"
          >
            <div v-if="(root.replyCount ?? 0) > 0 || isExpanded(root.commentId)" class="thread">
              <button
                v-if="!isExpanded(root.commentId)"
                type="button"
                class="thread__toggle"
                @click="toggleReplies(root)"
              >
                查看 {{ root.replyCount }} 条回复
              </button>
              <template v-else>
                <CommentItem
                  v-for="reply in threads[root.commentId]?.items ?? []"
                  :key="reply.commentId"
                  :comment="reply"
                  variant="reply"
                  :admin-mode="auth.isAdmin"
                  @reply="startReply"
                  @deleted="(id) => handleReplyDeleted(root.commentId, id)"
                />
                <div v-if="threads[root.commentId]?.loading" class="thread__hint">加载中…</div>
                <button
                  v-else-if="threads[root.commentId]?.hasMore"
                  type="button"
                  class="thread__toggle"
                  @click="loadReplies(root.commentId)"
                >
                  查看更多回复
                </button>
                <button type="button" class="thread__toggle thread__toggle--muted" @click="toggleReplies(root)">
                  收起回复
                </button>
              </template>
            </div>
          </CommentItem>
        </div>

        <LoadMore
          v-if="roots.items.value.length > 0"
          :has-more="roots.hasMore.value"
          :loading="roots.loadingMore.value"
          :error="roots.error.value"
          @load="roots.loadMore()"
        />
      </section>
    </template>
  </div>
</template>

<style scoped>
.back {
  border: none;
  background: transparent;
  color: var(--text-secondary);
  font-size: 13px;
  cursor: pointer;
  padding: 0 0 12px;
}
.back:hover {
  color: var(--primary);
}
.missing {
  padding: 20px;
}
.missing__title {
  font-weight: 600;
  margin-bottom: 4px;
}
.likes {
  margin-top: 16px;
  overflow: hidden;
}
.likes__toggle {
  width: 100%;
  display: flex;
  justify-content: space-between;
  align-items: center;
  padding: 14px 20px;
  border: none;
  background: transparent;
  font-size: 14px;
  font-weight: 600;
  cursor: pointer;
  color: var(--text-primary);
}
.likes__body {
  padding: 0 20px 16px;
}
.likes__list {
  list-style: none;
  margin: 0;
  padding: 0;
  display: flex;
  flex-direction: column;
  gap: 10px;
}
.likes__user {
  display: flex;
  align-items: center;
  gap: 10px;
  color: var(--text-primary);
}
.likes__nickname {
  display: block;
  font-size: 13px;
  font-weight: 600;
}
.likes__time {
  display: block;
  font-size: 12px;
  color: var(--text-muted);
}
.comments {
  margin-top: 16px;
  padding: 20px;
}
.comments__title {
  font-size: 16px;
  font-weight: 700;
  margin-bottom: 14px;
}
.composer {
  margin-bottom: 16px;
}
.composer__target {
  font-size: 13px;
  color: var(--text-muted);
  margin-bottom: 6px;
}
.composer__foot {
  display: flex;
  justify-content: space-between;
  align-items: center;
  margin-top: 8px;
}
.composer__error {
  color: var(--danger);
  font-size: 12px;
  margin-top: 6px;
}
.comment-list {
  border-top: 1px solid var(--border);
  padding-top: 6px;
}
.thread {
  margin-top: 6px;
  padding: 6px 12px;
  background: #fafbfc;
  border-radius: 12px;
}
.thread__toggle {
  border: none;
  background: transparent;
  color: var(--primary);
  font-size: 12px;
  font-weight: 600;
  cursor: pointer;
  padding: 4px 0;
}
.thread__toggle--muted {
  color: var(--text-muted);
  font-weight: 500;
}
.thread__hint {
  font-size: 12px;
  color: var(--text-muted);
  padding: 4px 0;
}
.link-btn {
  border: none;
  background: transparent;
  color: var(--text-muted);
  font-size: 12px;
  cursor: pointer;
  margin-left: 6px;
}
.link-btn:hover {
  color: var(--danger);
}
</style>

<script setup lang="ts">
import { computed, ref } from 'vue'
import { RouterLink } from 'vue-router'
import UserAvatar from './UserAvatar.vue'
import { commentApi } from '@/api/comment'
import { adminApi } from '@/api/admin'
import { describeError } from '@/api/http'
import type { CommentVO } from '@/api/types'
import { useAuthStore } from '@/stores/auth'
import { timeAgo } from '@/utils/format'

const props = withDefaults(
  defineProps<{
    comment: CommentVO
    /** 楼层视觉：一级评论为 root，回复缩进 */
    variant?: 'root' | 'reply'
    adminMode?: boolean
  }>(),
  { variant: 'root', adminMode: false },
)

const emit = defineEmits<{
  reply: [comment: CommentVO]
  deleted: [commentId: number]
}>()

const auth = useAuthStore()
const busy = ref(false)
const error = ref('')

const isOwner = computed(() => auth.user?.userId === props.comment.authorId)
const canDelete = computed(() => props.adminMode || isOwner.value)
const hasReplyTarget = computed(
  () => props.comment.replyUserId > 0 && Boolean(props.comment.replyUserNickname),
)

async function removeComment(): Promise<void> {
  const tip =
    props.variant === 'root'
      ? '删除该评论会连同其下全部回复一起消失，确定删除？'
      : '确定删除这条回复？'
  if (!window.confirm(tip)) return
  busy.value = true
  error.value = ''
  try {
    if (props.adminMode) await adminApi.removeComment(props.comment.commentId)
    else await commentApi.remove(props.comment.commentId)
    emit('deleted', props.comment.commentId)
  } catch (e) {
    error.value = describeError(e)
    busy.value = false
  }
}
</script>

<template>
  <div class="comment" :class="`comment--${variant}`">
    <RouterLink :to="`/users/${comment.authorId}`">
      <UserAvatar :src="comment.authorAvatarUrl" :name="comment.authorNickname" :size="32" />
    </RouterLink>
    <div class="comment__body">
      <div class="comment__meta">
        <RouterLink :to="`/users/${comment.authorId}`" class="comment__nickname">
          {{ comment.authorNickname }}
        </RouterLink>
        <span v-if="hasReplyTarget" class="comment__reply-to">
          回复 <span>@{{ comment.replyUserNickname }}</span>
        </span>
        <span class="comment__time">{{ timeAgo(comment.createTime) }}</span>
      </div>
      <p class="comment__content">{{ comment.content }}</p>
      <div class="comment__actions">
        <button type="button" class="link-btn" @click="emit('reply', comment)">回复</button>
        <button
          v-if="canDelete"
          type="button"
          class="link-btn link-btn--danger"
          :disabled="busy"
          @click="removeComment"
        >
          删除
        </button>
      </div>
      <p v-if="error" class="comment__error">{{ error }}</p>
      <slot />
    </div>
  </div>
</template>

<style scoped>
.comment {
  display: flex;
  gap: 10px;
  padding: 12px 0;
}
.comment--reply {
  padding: 8px 0;
}
.comment__body {
  flex: 1;
  min-width: 0;
}
.comment__meta {
  display: flex;
  align-items: center;
  gap: 8px;
  flex-wrap: wrap;
}
.comment__nickname {
  font-weight: 600;
  font-size: 13px;
  color: var(--text-primary);
}
.comment__nickname:hover {
  color: var(--primary);
}
.comment__reply-to {
  font-size: 12px;
  color: var(--text-muted);
}
.comment__reply-to span {
  color: var(--primary);
}
.comment__time {
  font-size: 12px;
  color: var(--text-muted);
}
.comment__content {
  margin-top: 4px;
  font-size: 14px;
  white-space: pre-wrap;
  word-break: break-word;
}
.comment__actions {
  display: flex;
  gap: 12px;
  margin-top: 2px;
}
.comment__error {
  color: var(--danger);
  font-size: 12px;
  margin-top: 4px;
}
.link-btn {
  border: none;
  background: transparent;
  color: var(--text-muted);
  font-size: 12px;
  cursor: pointer;
  padding: 0;
}
.link-btn:hover {
  color: var(--primary);
}
.link-btn--danger:hover {
  color: var(--danger);
}
</style>

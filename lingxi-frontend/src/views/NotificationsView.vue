<script setup lang="ts">
import { onMounted, ref } from 'vue'
import { useRouter } from 'vue-router'
import UserAvatar from '@/components/UserAvatar.vue'
import LoadMore from '@/components/LoadMore.vue'
import EmptyState from '@/components/EmptyState.vue'
import { notificationApi } from '@/api/notification'
import { describeError } from '@/api/http'
import type { NotificationVO } from '@/api/types'
import { useCursorList } from '@/composables/useCursorList'
import { useNotificationStore } from '@/stores/notification'
import { timeAgo } from '@/utils/format'

const router = useRouter()
const store = useNotificationStore()

const unreadOnly = ref(false)
const actionError = ref('')
const markingAll = ref(false)

const list = useCursorList<NotificationVO>(async (cursor, size) => {
  const page = await notificationApi.list({ cursor, size, unreadOnly: unreadOnly.value })
  // 列表响应里带最新角标，直接覆盖，不用再单独请求
  store.setUnreadCount(page.unreadCount)
  return { items: page.notificationList, nextCursor: page.nextCursor, hasMore: page.hasMore }
})

async function switchFilter(next: boolean): Promise<void> {
  if (unreadOnly.value === next) return
  unreadOnly.value = next
  list.reset()
  await list.loadFirst()
}

function targetOf(item: NotificationVO): string {
  // 关注通知没有关联动态，跳触发者主页
  if (item.type === 'FOLLOW') return `/users/${item.targetId}`
  if (item.type === 'COMMENT_POST' || item.type === 'REPLY_COMMENT') {
    return `/posts/${item.postId}`
  }
  return `/posts/${item.postId}`
}

async function open(item: NotificationVO): Promise<void> {
  actionError.value = ''
  try {
    if (!item.isRead) {
      const result = await notificationApi.markRead(item.notificationId)
      store.setUnreadCount(result.unreadCount)
      // 「只看未读」下已读的行不该继续留在列表里
      if (unreadOnly.value) {
        list.update(
          (row) => row.notificationId === item.notificationId,
          () => null,
        )
      } else {
        list.update(
          (row) => row.notificationId === item.notificationId,
          (row) => ({ ...row, isRead: true }),
        )
      }
    }
  } catch (error) {
    // 已读失败不阻塞跳转，只提示
    actionError.value = describeError(error)
  }
  if (item.postId === 0 && item.type !== 'FOLLOW') return
  await router.push(targetOf(item))
}

async function markAllRead(): Promise<void> {
  if (markingAll.value || store.unreadCount === 0) return
  markingAll.value = true
  actionError.value = ''
  try {
    const result = await notificationApi.markAllRead()
    store.setUnreadCount(result.unreadCount)
    if (unreadOnly.value) {
      // 「只看未读」下全部已读 = 列表应该空，重新拉一页保证与服务端一致
      list.reset()
      await list.loadFirst()
    } else {
      list.update(
        () => true,
        (row) => ({ ...row, isRead: true }),
      )
    }
  } catch (error) {
    actionError.value = describeError(error)
  } finally {
    markingAll.value = false
  }
}

onMounted(list.loadFirst)
</script>

<template>
  <div class="container">
    <header class="notif-head">
      <div>
        <h1 class="page-title">消息通知</h1>
        <p class="page-subtitle">{{ store.unreadCount }} 条未读</p>
      </div>
      <button
        type="button"
        class="btn btn--ghost btn--sm"
        :disabled="markingAll || store.unreadCount === 0"
        @click="markAllRead"
      >
        全部已读
      </button>
    </header>

    <nav class="tabs notif-tabs">
      <button
        type="button"
        class="tabs__item"
        :class="{ 'tabs__item--active': !unreadOnly }"
        @click="switchFilter(false)"
      >
        全部
      </button>
      <button
        type="button"
        class="tabs__item"
        :class="{ 'tabs__item--active': unreadOnly }"
        @click="switchFilter(true)"
      >
        只看未读
      </button>
    </nav>

    <p v-if="actionError" class="alert alert--error">{{ actionError }}</p>

    <div v-if="list.loading.value" class="center"><span class="spinner" /></div>

    <div v-else-if="list.error.value" class="alert alert--error">
      {{ list.error.value }}
      <button class="btn btn--ghost btn--sm" type="button" @click="list.loadFirst()">重试</button>
    </div>

    <EmptyState
      v-else-if="list.items.value.length === 0"
      icon="🔔"
      :title="unreadOnly ? '没有未读通知' : '还没有通知'"
      message="有人点赞、评论或关注你时会出现在这里"
    />

    <ul v-else class="card notif-list">
      <li
        v-for="item in list.items.value"
        :key="item.notificationId"
        class="notif-item"
        :class="{ 'notif-item--unread': !item.isRead }"
      >
        <button type="button" class="notif-item__button" @click="open(item)">
          <span class="notif-item__avatar">
            <UserAvatar :src="item.actorAvatarUrl" :name="item.actorNickname" :size="40" />
            <span v-if="!item.isRead" class="notif-item__dot" aria-label="未读" />
          </span>
          <span class="notif-item__body">
            <span class="notif-item__text">
              <strong>{{ item.actorNickname }}</strong>
              {{ item.typeDescription }}
            </span>
            <span class="notif-item__time">{{ timeAgo(item.createTime) }}</span>
          </span>
          <span class="notif-item__arrow" aria-hidden="true">›</span>
        </button>
      </li>
    </ul>

    <LoadMore
      v-if="list.items.value.length > 0"
      :has-more="list.hasMore.value"
      :loading="list.loadingMore.value"
      :error="list.error.value"
      @load="list.loadMore()"
    />
  </div>
</template>

<style scoped>
.notif-head {
  display: flex;
  justify-content: space-between;
  align-items: flex-start;
  margin-bottom: 14px;
}
.notif-tabs {
  margin-bottom: 16px;
}
.notif-list {
  list-style: none;
  margin: 0;
  padding: 0;
  overflow: hidden;
}
.notif-item {
  border-bottom: 1px solid var(--border);
}
.notif-item:last-child {
  border-bottom: none;
}
.notif-item--unread {
  background: #fbfbff;
}
.notif-item__button {
  width: 100%;
  display: flex;
  align-items: center;
  gap: 12px;
  padding: 14px 16px;
  border: none;
  background: transparent;
  text-align: left;
  cursor: pointer;
  font-family: inherit;
}
.notif-item__button:hover {
  background: var(--primary-soft);
}
.notif-item__avatar {
  position: relative;
  display: inline-flex;
}
.notif-item__dot {
  position: absolute;
  top: 0;
  right: -2px;
  width: 9px;
  height: 9px;
  border-radius: 50%;
  background: #ef4444;
  border: 2px solid #fff;
}
.notif-item__body {
  flex: 1;
  min-width: 0;
  display: flex;
  flex-direction: column;
  gap: 2px;
}
.notif-item__text {
  font-size: 14px;
  color: var(--text-primary);
}
.notif-item__text strong {
  font-weight: 600;
}
.notif-item__time {
  font-size: 12px;
  color: var(--text-muted);
}
.notif-item__arrow {
  color: var(--text-muted);
  font-size: 18px;
}
</style>

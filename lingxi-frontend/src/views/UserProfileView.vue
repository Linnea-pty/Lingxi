<script setup lang="ts">
import { computed, onMounted, ref, watch } from 'vue'
import { RouterLink, useRoute } from 'vue-router'
import UserAvatar from '@/components/UserAvatar.vue'
import LoadMore from '@/components/LoadMore.vue'
import EmptyState from '@/components/EmptyState.vue'
import { userApi } from '@/api/user'
import { ApiError, describeError } from '@/api/http'
import type { FollowUserVO, UserProfileVO } from '@/api/types'
import { useAuthStore } from '@/stores/auth'
import { useCursorList } from '@/composables/useCursorList'

const route = useRoute()
const auth = useAuthStore()

const userId = computed(() => Number(route.params.userId))
const profile = ref<UserProfileVO | null>(null)
const loading = ref(true)
const errorMessage = ref('')
const followBusy = ref(false)
const tab = ref<'following' | 'followers'>('following')

const following = useCursorList<FollowUserVO>(async (cursor, size) => {
  const page = await userApi.following(userId.value, cursor, size)
  return { items: page.userList, nextCursor: page.nextCursor, hasMore: page.hasMore }
})

const followers = useCursorList<FollowUserVO>(async (cursor, size) => {
  const page = await userApi.followers(userId.value, cursor, size)
  return { items: page.userList, nextCursor: page.nextCursor, hasMore: page.hasMore }
})

const activeList = computed(() => (tab.value === 'following' ? following : followers))
const isSelf = computed(() => auth.user?.userId === userId.value)

async function loadProfile(): Promise<void> {
  loading.value = true
  errorMessage.value = ''
  try {
    profile.value = await userApi.profile(userId.value)
  } catch (error) {
    if (error instanceof ApiError && error.code === 'USER_NOT_FOUND') {
      errorMessage.value = '用户不存在或已注销'
    } else {
      errorMessage.value = describeError(error)
    }
  } finally {
    loading.value = false
  }
}

async function toggleFollow(): Promise<void> {
  if (!profile.value || followBusy.value || isSelf.value) return
  followBusy.value = true
  const current = profile.value
  try {
    // 服务端返回操作后状态与最新粉丝数，直接覆盖本地
    const result = current.isFollowing
      ? await userApi.unfollow(current.userId)
      : await userApi.follow(current.userId)
    profile.value = {
      ...current,
      isFollowing: result.following,
      followerCount: result.followerCount,
    }
  } catch (error) {
    if (error instanceof ApiError && error.code === 'USER_FOLLOW_SELF') {
      errorMessage.value = '不能关注自己'
    } else {
      errorMessage.value = describeError(error)
    }
  } finally {
    followBusy.value = false
  }
}

async function switchTab(next: 'following' | 'followers'): Promise<void> {
  tab.value = next
  const list = next === 'following' ? following : followers
  if (!list.loaded.value) await list.loadFirst()
}

async function toggleRowFollow(user: FollowUserVO): Promise<void> {
  try {
    const result = user.isFollowing
      ? await userApi.unfollow(user.userId)
      : await userApi.follow(user.userId)
    activeList.value.update(
      (item) => item.userId === user.userId,
      (item) => ({ ...item, isFollowing: result.following }),
    )
  } catch (error) {
    errorMessage.value = describeError(error)
  }
}

onMounted(async () => {
  await loadProfile()
  if (!errorMessage.value) await switchTab('following')
})

watch(userId, async (next, previous) => {
  if (next === previous) return
  following.reset()
  followers.reset()
  await loadProfile()
  if (!errorMessage.value) await switchTab('following')
})
</script>

<template>
  <div class="container">
    <div v-if="loading" class="center"><span class="spinner" /></div>

    <div v-else-if="!profile" class="card profile-empty">
      <p class="profile-empty__title">{{ errorMessage || '用户信息不可用' }}</p>
      <RouterLink to="/" class="btn btn--ghost btn--sm">回到广场</RouterLink>
    </div>

    <template v-else>
      <section class="card profile">
        <UserAvatar :src="profile.avatarUrl" :name="profile.nickname" :size="72" />
        <div class="profile__info">
          <h1 class="profile__nickname">{{ profile.nickname }}</h1>
          <p class="profile__username">@{{ profile.username }}</p>
          <div class="profile__stats">
            <button type="button" class="stat" @click="switchTab('following')">
              <strong>{{ profile.followingCount }}</strong> 关注
            </button>
            <button type="button" class="stat" @click="switchTab('followers')">
              <strong>{{ profile.followerCount }}</strong> 粉丝
            </button>
          </div>
        </div>
        <div class="profile__actions">
          <RouterLink v-if="isSelf" to="/me" class="btn btn--ghost btn--sm">编辑资料</RouterLink>
          <button
            v-else
            type="button"
            class="btn btn--sm"
            :class="{ 'btn--ghost': profile.isFollowing }"
            :disabled="followBusy"
            @click="toggleFollow"
          >
            {{ profile.isFollowing ? '已关注' : '+ 关注' }}
          </button>
        </div>
      </section>

      <p v-if="errorMessage" class="alert alert--error profile__error">{{ errorMessage }}</p>

      <nav class="tabs profile__tabs">
        <button
          type="button"
          class="tabs__item"
          :class="{ 'tabs__item--active': tab === 'following' }"
          @click="switchTab('following')"
        >
          关注
        </button>
        <button
          type="button"
          class="tabs__item"
          :class="{ 'tabs__item--active': tab === 'followers' }"
          @click="switchTab('followers')"
        >
          粉丝
        </button>
      </nav>

      <div v-if="activeList.loading.value" class="center"><span class="spinner" /></div>

      <div v-else-if="activeList.error.value" class="alert alert--error">
        {{ activeList.error.value }}
        <button class="btn btn--ghost btn--sm" type="button" @click="activeList.loadFirst()">重试</button>
      </div>

      <EmptyState
        v-else-if="activeList.items.value.length === 0"
        icon="👀"
        :title="tab === 'following' ? '还没有关注任何人' : '还没有粉丝'"
        :message="tab === 'following' ? '去广场看看谁值得关注' : '多发几条动态，会有人来的'"
      />

      <ul v-else class="card user-list">
        <li v-for="user in activeList.items.value" :key="user.userId" class="user-list__item">
          <RouterLink :to="`/users/${user.userId}`" class="user-list__profile">
            <UserAvatar :src="user.avatarUrl" :name="user.nickname" :size="40" />
            <span>
              <span class="user-list__nickname">{{ user.nickname }}</span>
              <span class="user-list__username">@{{ user.username }}</span>
            </span>
          </RouterLink>
          <button
            v-if="user.userId !== auth.user?.userId"
            type="button"
            class="btn btn--sm"
            :class="{ 'btn--ghost': user.isFollowing }"
            @click="toggleRowFollow(user)"
          >
            {{ user.isFollowing ? '已关注' : '+ 关注' }}
          </button>
        </li>
      </ul>

      <LoadMore
        v-if="activeList.items.value.length > 0"
        :has-more="activeList.hasMore.value"
        :loading="activeList.loadingMore.value"
        :error="activeList.error.value"
        @load="activeList.loadMore()"
      />
    </template>
  </div>
</template>

<style scoped>
.profile {
  display: flex;
  gap: 18px;
  align-items: center;
  padding: 24px 20px;
}
.profile__info {
  flex: 1;
  min-width: 0;
}
.profile__nickname {
  font-size: 20px;
  font-weight: 700;
}
.profile__username {
  font-size: 13px;
  color: var(--text-muted);
}
.profile__stats {
  display: flex;
  gap: 18px;
  margin-top: 8px;
  font-size: 13px;
  color: var(--text-muted);
}
.stat {
  border: none;
  background: transparent;
  font-size: 13px;
  color: var(--text-muted);
  cursor: pointer;
  padding: 0;
}
.stat strong {
  color: var(--text-primary);
  font-size: 15px;
  margin-right: 2px;
}
.profile__actions {
  align-self: flex-start;
}
.profile__error {
  margin-top: 12px;
}
.profile__tabs {
  margin-top: 18px;
}
.profile-empty {
  padding: 32px 24px;
  text-align: center;
  display: flex;
  flex-direction: column;
  align-items: center;
  gap: 14px;
}
.profile-empty__title {
  font-weight: 600;
}
.user-list {
  list-style: none;
  margin: 12px 0 0;
  padding: 4px 8px;
}
.user-list__item {
  display: flex;
  align-items: center;
  justify-content: space-between;
  gap: 12px;
  padding: 12px;
  border-bottom: 1px solid var(--border);
}
.user-list__item:last-child {
  border-bottom: none;
}
.user-list__profile {
  display: flex;
  align-items: center;
  gap: 12px;
  color: var(--text-primary);
  min-width: 0;
}
.user-list__nickname {
  display: block;
  font-weight: 600;
  font-size: 14px;
}
.user-list__username {
  display: block;
  font-size: 12px;
  color: var(--text-muted);
}
</style>

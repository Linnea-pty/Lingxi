<script setup lang="ts">
import { computed, onBeforeUnmount, watch } from 'vue'
import { RouterLink, RouterView, useRoute, useRouter } from 'vue-router'
import UserAvatar from '@/components/UserAvatar.vue'
import { useAuthStore } from '@/stores/auth'
import { useNotificationStore } from '@/stores/notification'

const auth = useAuthStore()
const notifications = useNotificationStore()
const route = useRoute()
const router = useRouter()

const showShell = computed(() => auth.isLoggedIn && !route.meta.guestOnly)
const unreadBadge = computed(() => (notifications.unreadCount > 99 ? '99+' : `${notifications.unreadCount}`))
const myUserId = computed(() => auth.user?.userId ?? 0)
const myAvatar = computed(() => auth.user?.avatarUrl ?? '')

let pollTimer: ReturnType<typeof setInterval> | null = null

function startPolling(): void {
  stopPolling()
  // 角标只认服务端数字，定时刷新即可，不必拉整页列表
  pollTimer = setInterval(() => notifications.refreshUnreadCount(), 60_000)
}

function stopPolling(): void {
  if (pollTimer) clearInterval(pollTimer)
  pollTimer = null
}

async function handleLogout(): Promise<void> {
  stopPolling()
  await auth.logout()
  notifications.reset()
  await router.replace('/login')
}

// 登录态变化时启停角标轮询；登录后顺手用当前用户接口校准缓存资料
watch(
  () => auth.isLoggedIn,
  (loggedIn) => {
    if (!loggedIn) {
      stopPolling()
      notifications.reset()
      return
    }
    startPolling()
    notifications.refreshUnreadCount()
    auth.fetchCurrentUser().catch(() => {
      // 401 已由请求层统一处理，其余错误忽略
    })
  },
  { immediate: true },
)

watch(
  () => route.fullPath,
  () => {
    if (auth.isLoggedIn) notifications.refreshUnreadCount()
  },
)

onBeforeUnmount(stopPolling)
</script>

<template>
  <div class="app-shell">
    <header v-if="showShell" class="topbar">
      <div class="topbar__inner">
        <RouterLink to="/" class="topbar__brand">
          <span class="topbar__logo">灵犀</span>
        </RouterLink>

        <nav class="topbar__nav">
          <RouterLink to="/" class="topbar__link" exact-active-class="topbar__link--active">
            动态广场
          </RouterLink>
          <RouterLink to="/me" class="topbar__link" active-class="topbar__link--active">
            个人中心
          </RouterLink>
          <RouterLink
            v-if="auth.isAdmin"
            to="/admin/users"
            class="topbar__link"
            active-class="topbar__link--active"
          >
            用户管理
          </RouterLink>
        </nav>

        <div class="topbar__right">
          <RouterLink to="/notifications" class="topbar__bell" aria-label="消息通知">
            <span aria-hidden="true">🔔</span>
            <span v-if="notifications.unreadCount > 0" class="topbar__badge">{{ unreadBadge }}</span>
          </RouterLink>

          <RouterLink v-if="auth.user" :to="`/users/${myUserId}`" class="topbar__me">
            <UserAvatar :src="myAvatar" :name="auth.displayName" :size="32" />
            <span class="topbar__nickname">{{ auth.displayName }}</span>
          </RouterLink>

          <button type="button" class="btn btn--ghost btn--sm" @click="handleLogout">退出</button>
        </div>
      </div>
    </header>

    <main class="app-main">
      <RouterView />
    </main>
  </div>
</template>

<style scoped>
.app-main {
  flex: 1;
}
.topbar {
  position: sticky;
  top: 0;
  z-index: 20;
  background: rgba(255, 255, 255, 0.88);
  backdrop-filter: saturate(180%) blur(12px);
  border-bottom: 1px solid var(--border);
}
.topbar__inner {
  max-width: 1040px;
  margin: 0 auto;
  height: 60px;
  padding: 0 16px;
  display: flex;
  align-items: center;
  gap: 20px;
}
.topbar__brand {
  display: flex;
  align-items: center;
}
.topbar__logo {
  font-size: 19px;
  font-weight: 800;
  letter-spacing: 0.08em;
  background: linear-gradient(120deg, #4f46e5, #a855f7);
  -webkit-background-clip: text;
  background-clip: text;
  color: transparent;
}
.topbar__nav {
  display: flex;
  gap: 4px;
  flex: 1;
}
.topbar__link {
  padding: 6px 12px;
  border-radius: 999px;
  color: var(--text-secondary);
  font-weight: 500;
  font-size: 14px;
}
.topbar__link:hover {
  background: #f3f4f6;
  color: var(--text-primary);
}
.topbar__link--active {
  background: var(--primary-soft);
  color: var(--primary);
  font-weight: 600;
}
.topbar__right {
  display: flex;
  align-items: center;
  gap: 10px;
}
.topbar__bell {
  position: relative;
  display: inline-flex;
  width: 36px;
  height: 36px;
  align-items: center;
  justify-content: center;
  border-radius: 50%;
  font-size: 17px;
}
.topbar__bell:hover {
  background: #f3f4f6;
}
.topbar__badge {
  position: absolute;
  top: 0;
  right: -2px;
  min-width: 17px;
  height: 17px;
  padding: 0 4px;
  border-radius: 999px;
  background: #ef4444;
  color: #fff;
  font-size: 10px;
  font-weight: 700;
  line-height: 17px;
  text-align: center;
  border: 1px solid #fff;
}
.topbar__me {
  display: flex;
  align-items: center;
  gap: 6px;
  color: var(--text-primary);
}
.topbar__nickname {
  font-size: 13px;
  font-weight: 600;
  max-width: 90px;
  overflow: hidden;
  text-overflow: ellipsis;
  white-space: nowrap;
}
@media (max-width: 640px) {
  .topbar__nickname {
    display: none;
  }
  .topbar__link {
    padding: 6px 8px;
    font-size: 13px;
  }
}
</style>

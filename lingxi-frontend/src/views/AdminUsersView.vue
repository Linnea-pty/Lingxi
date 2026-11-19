<script setup lang="ts">
import { onMounted, ref } from 'vue'
import { RouterLink } from 'vue-router'
import UserAvatar from '@/components/UserAvatar.vue'
import LoadMore from '@/components/LoadMore.vue'
import EmptyState from '@/components/EmptyState.vue'
import { adminApi } from '@/api/admin'
import { describeError } from '@/api/http'
import type { AdminUserVO } from '@/api/types'
import { useCursorList } from '@/composables/useCursorList'
import { timeAgo } from '@/utils/format'

const keyword = ref('')
const status = ref<'' | '1' | '0'>('')
const actionError = ref('')
const actionMessage = ref('')

const banned = ref<AdminUserVO | null>(null)
const banReason = ref('')
const banBusy = ref(false)

const list = useCursorList<AdminUserVO>(async (cursor, size) => {
  const page = await adminApi.users({
    cursor,
    size,
    keyword: keyword.value.trim() || undefined,
    // 0 已封禁 / 1 正常 / 不传为全部
    status: status.value === '' ? undefined : (Number(status.value) as 0 | 1),
  })
  return { items: page.userList, nextCursor: page.nextCursor, hasMore: page.hasMore }
})

async function search(): Promise<void> {
  actionError.value = ''
  actionMessage.value = ''
  list.reset()
  await list.loadFirst()
}

function openBan(user: AdminUserVO): void {
  banned.value = user
  banReason.value = ''
  actionError.value = ''
}

async function confirmBan(): Promise<void> {
  if (!banned.value || banBusy.value) return
  banBusy.value = true
  actionError.value = ''
  try {
    const updated = await adminApi.ban(banned.value.userId, banReason.value.trim() || undefined)
    list.update(
      (user) => user.userId === updated.userId,
      () => updated,
    )
    actionMessage.value = `已封禁 ${updated.nickname || updated.username}`
    banned.value = null
  } catch (error) {
    actionError.value = describeError(error)
  } finally {
    banBusy.value = false
  }
}

async function unban(user: AdminUserVO): Promise<void> {
  actionError.value = ''
  actionMessage.value = ''
  try {
    const updated = await adminApi.unban(user.userId)
    list.update(
      (item) => item.userId === updated.userId,
      () => updated,
    )
    actionMessage.value = `已解封 ${updated.nickname || updated.username}`
  } catch (error) {
    actionError.value = describeError(error)
  }
}

onMounted(list.loadFirst)
</script>

<template>
  <div class="container container--wide">
    <header class="admin-head">
      <div>
        <h1 class="page-title">用户管理</h1>
        <p class="page-subtitle">按用户名 / 昵称前缀查找，注册时间倒序</p>
      </div>
      <RouterLink to="/" class="btn btn--ghost btn--sm">回到广场</RouterLink>
    </header>

    <section class="card filters">
      <form class="filters__form" @submit.prevent="search">
        <input
          v-model="keyword"
          class="input filters__input"
          type="search"
          maxlength="32"
          placeholder="用户名或昵称前缀"
        />
        <select v-model="status" class="input filters__select">
          <option value="">全部状态</option>
          <option value="1">正常</option>
          <option value="0">已封禁</option>
        </select>
        <button class="btn btn--sm" type="submit">查询</button>
      </form>
      <p class="muted text-sm filters__note">后台删除动态 / 评论可在广场与详情页的卡片上操作。</p>
    </section>

    <p v-if="actionError" class="alert alert--error">{{ actionError }}</p>
    <p v-if="actionMessage" class="alert alert--info">{{ actionMessage }}</p>

    <div v-if="list.loading.value" class="center"><span class="spinner" /></div>

    <div v-else-if="list.error.value" class="alert alert--error">
      {{ list.error.value }}
      <button class="btn btn--ghost btn--sm" type="button" @click="list.loadFirst()">重试</button>
    </div>

    <EmptyState v-else-if="list.items.value.length === 0" icon="🔍" title="没有匹配的用户" />

    <div v-else class="card admin-table">
      <div class="admin-table__head">
        <span>用户</span>
        <span>角色 / 状态</span>
        <span>注册时间</span>
        <span class="admin-table__actions">操作</span>
      </div>
      <div v-for="user in list.items.value" :key="user.userId" class="admin-row">
        <div class="admin-row__user">
          <UserAvatar :src="user.avatarUrl" :name="user.nickname" :size="38" />
          <div class="admin-row__names">
            <RouterLink :to="`/users/${user.userId}`" class="admin-row__nickname">
              {{ user.nickname || user.username }}
            </RouterLink>
            <span class="admin-row__username">@{{ user.username }} · ID {{ user.userId }}</span>
          </div>
        </div>

        <div class="admin-row__tags">
          <span class="tag" :class="{ 'tag--admin': user.role === 'ADMIN' }">{{ user.roleDescription }}</span>
          <span class="tag" :class="user.status === 1 ? 'tag--agent' : 'tag--pinned'">
            {{ user.statusDescription }}
          </span>
          <span v-if="user.banReason" class="admin-row__reason" :title="user.banReason">
            原因：{{ user.banReason }}
          </span>
        </div>

        <span class="admin-row__time">{{ timeAgo(user.createTime) }}</span>

        <div class="admin-row__actions">
          <button
            v-if="user.status === 1"
            type="button"
            class="btn btn--danger btn--sm"
            @click="openBan(user)"
          >
            封禁
          </button>
          <button v-else type="button" class="btn btn--soft btn--sm" @click="unban(user)">
            解封
          </button>
        </div>
      </div>
    </div>

    <LoadMore
      v-if="list.items.value.length > 0"
      :has-more="list.hasMore.value"
      :loading="list.loadingMore.value"
      :error="list.error.value"
      @load="list.loadMore()"
    />

    <div v-if="banned" class="modal">
      <div class="modal__panel card">
        <h2 class="modal__title">封禁 {{ banned.nickname || banned.username }}</h2>
        <p class="muted text-sm modal__note">
          封禁立即生效：该账号的旧访问令牌、刷新令牌与 Agent 密钥都会在下一次请求时被拒绝。
        </p>
        <label class="field">
          <span class="field__label">封禁原因（选填，≤200 字）</span>
          <textarea v-model="banReason" class="textarea" rows="3" maxlength="200" />
        </label>
        <div class="modal__actions">
          <button type="button" class="btn btn--ghost btn--sm" :disabled="banBusy" @click="banned = null">
            取消
          </button>
          <button type="button" class="btn btn--danger btn--sm" :disabled="banBusy" @click="confirmBan">
            {{ banBusy ? '处理中…' : '确认封禁' }}
          </button>
        </div>
      </div>
    </div>
  </div>
</template>

<style scoped>
.admin-head {
  display: flex;
  justify-content: space-between;
  align-items: flex-start;
  margin-bottom: 16px;
}
.filters {
  padding: 16px;
  margin-bottom: 16px;
}
.filters__form {
  display: flex;
  gap: 8px;
  flex-wrap: wrap;
}
.filters__input {
  flex: 1;
  min-width: 200px;
}
.filters__select {
  width: 130px;
}
.filters__note {
  margin-top: 8px;
}
.admin-table {
  overflow: hidden;
}
.admin-table__head {
  display: grid;
  grid-template-columns: 2fr 1.4fr 1fr 0.8fr;
  gap: 12px;
  padding: 12px 16px;
  font-size: 12px;
  font-weight: 600;
  color: var(--text-muted);
  background: #fafbfc;
  border-bottom: 1px solid var(--border);
}
.admin-row {
  display: grid;
  grid-template-columns: 2fr 1.4fr 1fr 0.8fr;
  gap: 12px;
  align-items: center;
  padding: 12px 16px;
  border-bottom: 1px solid var(--border);
}
.admin-row:last-child {
  border-bottom: none;
}
.admin-row__user {
  display: flex;
  gap: 10px;
  align-items: center;
  min-width: 0;
}
.admin-row__names {
  min-width: 0;
}
.admin-row__nickname {
  display: block;
  font-weight: 600;
  font-size: 14px;
  color: var(--text-primary);
  overflow: hidden;
  text-overflow: ellipsis;
  white-space: nowrap;
}
.admin-row__nickname:hover {
  color: var(--primary);
}
.admin-row__username {
  display: block;
  font-size: 12px;
  color: var(--text-muted);
}
.admin-row__tags {
  display: flex;
  flex-wrap: wrap;
  gap: 6px;
  align-items: center;
  min-width: 0;
}
.admin-row__reason {
  font-size: 11px;
  color: var(--danger);
  width: 100%;
  overflow: hidden;
  text-overflow: ellipsis;
  white-space: nowrap;
}
.admin-row__time {
  font-size: 12px;
  color: var(--text-muted);
}
.admin-row__actions {
  display: flex;
  justify-content: flex-end;
}
.modal {
  position: fixed;
  inset: 0;
  background: rgba(17, 24, 39, 0.45);
  display: flex;
  align-items: center;
  justify-content: center;
  padding: 20px;
  z-index: 50;
}
.modal__panel {
  width: 100%;
  max-width: 420px;
  padding: 22px;
}
.modal__title {
  font-size: 17px;
  font-weight: 700;
  margin-bottom: 6px;
}
.modal__note {
  margin-bottom: 14px;
}
.modal__actions {
  display: flex;
  justify-content: flex-end;
  gap: 8px;
}
@media (max-width: 720px) {
  .admin-table__head {
    display: none;
  }
  .admin-row {
    grid-template-columns: 1fr;
    gap: 8px;
  }
  .admin-row__actions {
    justify-content: flex-start;
  }
}
</style>

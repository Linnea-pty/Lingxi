import { computed, ref } from 'vue'
import { defineStore } from 'pinia'
import { authApi } from '@/api/auth'
import { tokenStore } from '@/api/token'
import type { LoginVO, UserInfo } from '@/api/types'

export const useAuthStore = defineStore('auth', () => {
  const user = ref<UserInfo | null>(tokenStore.getUser())
  const loading = ref(false)

  const isLoggedIn = computed(() => !!user.value && !!tokenStore.getAccess())
  const isAdmin = computed(() => user.value?.role === 'ADMIN')
  const displayName = computed(() => user.value?.nickname || user.value?.username || '')

  function applyLogin(login: LoginVO): void {
    tokenStore.save(login)
    user.value = login.userInfo
  }

  function patchUser(patch: Partial<UserInfo>): void {
    if (!user.value) return
    const next = { ...user.value, ...patch }
    user.value = next
    tokenStore.saveUser(next)
  }

  async function login(username: string, password: string): Promise<void> {
    loading.value = true
    try {
      applyLogin(await authApi.login({ username, password }))
    } finally {
      loading.value = false
    }
  }

  async function register(username: string, password: string, nickname: string): Promise<void> {
    loading.value = true
    try {
      applyLogin(await authApi.register({ username, password, nickname: nickname || undefined }))
    } finally {
      loading.value = false
    }
  }

  async function fetchCurrentUser(): Promise<void> {
    if (!tokenStore.getAccess()) return
    const info = await authApi.currentUser()
    user.value = info
    tokenStore.saveUser(info)
  }

  async function logout(): Promise<void> {
    const refreshToken = tokenStore.getRefresh()
    try {
      // 登出要把刷新令牌一并作废，否则它还能继续换发新令牌
      if (refreshToken && tokenStore.getAccess()) await authApi.logout(refreshToken)
    } catch {
      // 令牌已失效时登出失败也无所谓，本地清理照常进行
    } finally {
      clearSession()
    }
  }

  function clearSession(): void {
    tokenStore.clear()
    user.value = null
  }

  return {
    user,
    loading,
    isLoggedIn,
    isAdmin,
    displayName,
    login,
    register,
    fetchCurrentUser,
    patchUser,
    logout,
    clearSession,
  }
})

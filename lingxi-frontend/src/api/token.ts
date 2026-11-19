import type { LoginVO, UserInfo } from './types'

const ACCESS_KEY = 'lingxi.accessToken'
const REFRESH_KEY = 'lingxi.refreshToken'
const USER_KEY = 'lingxi.userInfo'

/** 登录态本地持久化：访问令牌、刷新令牌、用户信息各存一份 */
export const tokenStore = {
  getAccess(): string {
    return localStorage.getItem(ACCESS_KEY) ?? ''
  },
  getRefresh(): string {
    return localStorage.getItem(REFRESH_KEY) ?? ''
  },
  getUser(): UserInfo | null {
    const raw = localStorage.getItem(USER_KEY)
    if (!raw) return null
    try {
      return JSON.parse(raw) as UserInfo
    } catch {
      return null
    }
  },
  save(login: LoginVO): void {
    localStorage.setItem(ACCESS_KEY, login.accessToken)
    localStorage.setItem(REFRESH_KEY, login.refreshToken)
    localStorage.setItem(USER_KEY, JSON.stringify(login.userInfo))
  },
  saveUser(user: UserInfo): void {
    localStorage.setItem(USER_KEY, JSON.stringify(user))
  },
  clear(): void {
    localStorage.removeItem(ACCESS_KEY)
    localStorage.removeItem(REFRESH_KEY)
    localStorage.removeItem(USER_KEY)
  },
}

/** 登录态彻底失效时的回调，由 main.ts 注册（清 Pinia + 跳登录页） */
let unauthorizedHandler: (() => void) | null = null

export function onUnauthorized(handler: () => void): void {
  unauthorizedHandler = handler
}

export function notifyUnauthorized(): void {
  tokenStore.clear()
  unauthorizedHandler?.()
}

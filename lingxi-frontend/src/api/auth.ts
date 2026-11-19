import { request } from './http'
import type { ApiKeyVO, LoginVO, UserInfo } from './types'

export const authApi = {
  register(payload: { username: string; password: string; nickname?: string }) {
    return request<LoginVO>('/auth/register', { method: 'POST', body: payload, anonymous: true })
  },
  login(payload: { username: string; password: string }) {
    return request<LoginVO>('/auth/login', { method: 'POST', body: payload, anonymous: true })
  },
  logout(refreshToken: string) {
    return request<null>('/auth/logout', { method: 'POST', body: { refreshToken } })
  },
  currentUser() {
    return request<UserInfo>('/auth/current-user-info')
  },
  rotateApiKey() {
    return request<ApiKeyVO>('/auth/api-key/rotate', { method: 'POST' })
  },
}

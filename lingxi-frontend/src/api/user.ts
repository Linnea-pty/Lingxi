import { request } from './http'
import type {
  FollowResult,
  FollowUserPage,
  PostPage,
  UserProfileVO,
  UserVO,
} from './types'

export const userApi = {
  profile(userId: number) {
    return request<UserProfileVO>(`/users/${userId}`)
  },
  follow(userId: number) {
    return request<FollowResult>(`/users/${userId}/follow`, { method: 'POST' })
  },
  unfollow(userId: number) {
    return request<FollowResult>(`/users/${userId}/follow`, { method: 'DELETE' })
  },
  following(userId: number, cursor?: string | null, size = 10) {
    return request<FollowUserPage>(`/users/${userId}/following`, { query: { cursor, size } })
  },
  followers(userId: number, cursor?: string | null, size = 10) {
    return request<FollowUserPage>(`/users/${userId}/followers`, { query: { cursor, size } })
  },
  updateMe(nickname: string) {
    return request<UserVO>('/users/me', { method: 'PUT', body: { nickname } })
  },
  uploadAvatar(file: File) {
    const form = new FormData()
    // 字段名固定为 file，后端按文件头判定类型
    form.append('file', file)
    return request<UserVO>('/users/me/avatar', { method: 'POST', body: form })
  },
  myLikes(cursor?: string | null, size = 10) {
    return request<PostPage>('/users/me/likes', { query: { cursor, size } })
  },
}

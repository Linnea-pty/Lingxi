import { request } from './http'
import type { AdminUserPage, AdminUserVO } from './types'

export const adminApi = {
  users(params: { cursor?: string | null; size?: number; keyword?: string; status?: 0 | 1 }) {
    return request<AdminUserPage>('/admin/users', {
      query: {
        cursor: params.cursor,
        size: params.size ?? 10,
        keyword: params.keyword,
        status: params.status,
      },
    })
  },
  ban(userId: number, reason?: string) {
    return request<AdminUserVO>(`/admin/users/${userId}/ban`, {
      method: 'POST',
      body: reason ? { reason } : {},
    })
  },
  unban(userId: number) {
    return request<AdminUserVO>(`/admin/users/${userId}/unban`, { method: 'POST' })
  },
  removePost(postId: number) {
    return request<null>(`/admin/posts/${postId}`, { method: 'DELETE' })
  },
  removeComment(commentId: number) {
    return request<null>(`/admin/comments/${commentId}`, { method: 'DELETE' })
  },
}

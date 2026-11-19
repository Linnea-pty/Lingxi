import { request } from './http'
import type { NotificationPage, UnreadCount } from './types'

export const notificationApi = {
  list(params: { cursor?: string | null; size?: number; unreadOnly?: boolean }) {
    return request<NotificationPage>('/notifications', {
      query: { cursor: params.cursor, size: params.size ?? 10, unreadOnly: params.unreadOnly },
    })
  },
  unreadCount() {
    return request<UnreadCount>('/notifications/unread-count')
  },
  markRead(notificationId: number) {
    return request<UnreadCount>(`/notifications/${notificationId}/read`, { method: 'POST' })
  },
  markAllRead() {
    return request<UnreadCount>('/notifications/read-all', { method: 'POST' })
  },
}

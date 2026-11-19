import { request } from './http'
import type { CommentPage, CommentVO } from './types'

export const commentApi = {
  create(postId: number, payload: { content: string; parentId?: number; requestId?: string }) {
    return request<CommentVO>(`/posts/${postId}/comments`, { method: 'POST', body: payload })
  },
  /** 一级评论：按时间倒序 */
  listRoots(postId: number, cursor?: string | null, size = 10) {
    return request<CommentPage>(`/posts/${postId}/comments`, { query: { cursor, size } })
  },
  /** 讨论串回复：按时间正序 */
  listReplies(rootCommentId: number, cursor?: string | null, size = 10) {
    return request<CommentPage>(`/comments/${rootCommentId}/replies`, { query: { cursor, size } })
  },
  remove(commentId: number) {
    return request<null>(`/comments/${commentId}`, { method: 'DELETE' })
  },
  mine(cursor?: string | null, size = 10) {
    return request<CommentPage>('/users/me/comments', { query: { cursor, size } })
  },
}

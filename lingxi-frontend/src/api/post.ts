import { request } from './http'
import type { LikeResult, LikeUserPage, PostPage, PostVO } from './types'

export const postApi = {
  create(payload: { content: string; requestId?: string }) {
    return request<PostVO>('/posts', { method: 'POST', body: payload })
  },
  feed(cursor?: string | null, size = 10) {
    return request<PostPage>('/posts/feed', { query: { cursor, size } })
  },
  update(postId: number, content: string) {
    return request<PostVO>(`/posts/${postId}`, { method: 'PUT', body: { content } })
  },
  remove(postId: number) {
    return request<null>(`/posts/${postId}`, { method: 'DELETE' })
  },
  like(postId: number) {
    return request<LikeResult>(`/posts/${postId}/like`, { method: 'POST' })
  },
  unlike(postId: number) {
    return request<LikeResult>(`/posts/${postId}/like`, { method: 'DELETE' })
  },
  likeUsers(postId: number, cursor?: string | null, size = 10) {
    return request<LikeUserPage>(`/posts/${postId}/likes`, { query: { cursor, size } })
  },
}

/** 统一返回体：业务成败只看 code，"0" 为成功 */
export interface ApiResponse<T> {
  code: string
  message: string
  data: T
}

/** 键集游标分页的公共结构 */
export interface CursorPage {
  nextCursor: string | null
  hasMore: boolean
}

export interface UserInfo {
  userId: number
  username: string
  nickname: string
  avatarUrl: string
  role: 'USER' | 'ADMIN'
  roleDescription: string
}

export interface LoginVO {
  accessToken: string
  expiresIn: number
  refreshToken: string
  refreshExpiresIn: number
  userInfo: UserInfo
}

export interface UserVO {
  userId: number
  username: string
  nickname: string
  avatarUrl: string
  role?: 'USER' | 'ADMIN'
  roleDescription?: string
}

export interface PostVO {
  postId: number
  content: string
  source: 'web' | 'agent'
  isPinned: boolean
  createTime: string
  authorId: number
  authorNickname: string
  authorAvatarUrl: string
  likeCount: number
  commentCount: number
  likedAt?: string | null
}

export interface PostPage extends CursorPage {
  postList: PostVO[]
}

export interface LikeResult {
  liked: boolean
  likeCount: number
}

export interface LikeUserVO {
  userId: number
  username: string
  nickname: string
  avatarUrl: string
  likedAt: string
}

export interface LikeUserPage extends CursorPage {
  userList: LikeUserVO[]
}

export interface CommentVO {
  commentId: number
  postId: number
  parentId: number
  rootId: number
  content: string
  createTime: string
  authorId: number
  authorNickname: string
  authorAvatarUrl: string
  replyUserId: number
  replyUserNickname: string
  replyCount: number | null
}

export interface CommentPage extends CursorPage {
  commentList: CommentVO[]
}

export interface UserProfileVO {
  userId: number
  username: string
  nickname: string
  avatarUrl: string
  followerCount: number
  followingCount: number
  isFollowing: boolean
}

export interface FollowResult {
  following: boolean
  followerCount: number
}

export interface FollowUserVO {
  userId: number
  username: string
  nickname: string
  avatarUrl: string
  followedAt: string
  isFollowing: boolean
}

export interface FollowUserPage extends CursorPage {
  userList: FollowUserVO[]
}

export type NotificationType = 'LIKE_POST' | 'COMMENT_POST' | 'REPLY_COMMENT' | 'FOLLOW'

export interface NotificationVO {
  notificationId: number
  type: NotificationType
  typeDescription: string
  actorId: number
  actorUsername: string
  actorNickname: string
  actorAvatarUrl: string
  targetId: number
  postId: number
  isRead: boolean
  createTime: string
}

export interface NotificationPage extends CursorPage {
  notificationList: NotificationVO[]
  unreadCount: number
}

export interface UnreadCount {
  unreadCount: number
}

export interface ApiKeyVO {
  apiKey: string
  generatedAt: string
  usage: string
}

export interface AdminUserVO {
  userId: number
  username: string
  nickname: string
  avatarUrl: string
  role: 'USER' | 'ADMIN'
  roleDescription: string
  status: 0 | 1
  statusDescription: string
  banReason: string
  createTime: string
}

export interface AdminUserPage extends CursorPage {
  userList: AdminUserVO[]
}

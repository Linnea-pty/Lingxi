import { notifyUnauthorized, tokenStore } from './token'
import type { ApiResponse, LoginVO } from './types'

/** 接口根路径，可在 .env 里用 VITE_API_BASE_URL 覆盖 */
export const API_BASE_URL = (
  import.meta.env.VITE_API_BASE_URL || 'http://localhost:18080/api/v1'
).replace(/\/+$/, '')

/** 静态资源（头像等）根路径：去掉 /api/v1 前缀，头像地址直接拼在这里 */
export const ASSET_BASE_URL = API_BASE_URL.replace(/\/api\/v\d+$/, '')

export class ApiError extends Error {
  readonly code: string
  readonly status: number

  constructor(code: string, message: string, status: number) {
    super(message)
    this.name = 'ApiError'
    this.code = code
    this.status = status
  }
}

type Query = Record<string, string | number | boolean | null | undefined>

export interface RequestOptions {
  method?: 'GET' | 'POST' | 'PUT' | 'DELETE'
  query?: Query
  body?: unknown
  /** 不携带令牌（登录、注册、刷新） */
  anonymous?: boolean
  /** 401 时不做自动刷新重放（刷新接口自身） */
  skipRefresh?: boolean
}

function buildUrl(path: string, query?: Query): string {
  const url = new URL(API_BASE_URL + path)
  if (query) {
    for (const [key, value] of Object.entries(query)) {
      if (value === undefined || value === null || value === '') continue
      url.searchParams.set(key, String(value))
    }
  }
  return url.toString()
}

async function send(path: string, options: RequestOptions): Promise<Response> {
  const headers: Record<string, string> = { Accept: 'application/json' }
  const accessToken = tokenStore.getAccess()
  if (!options.anonymous && accessToken) {
    headers.Authorization = `Bearer ${accessToken}`
  }

  let body: BodyInit | undefined
  if (options.body instanceof FormData) {
    body = options.body
  } else if (options.body !== undefined) {
    headers['Content-Type'] = 'application/json'
    body = JSON.stringify(options.body)
  }

  return fetch(buildUrl(path, options.query), {
    method: options.method ?? 'GET',
    headers,
    body,
  })
}

/** 同一时刻只允许一个刷新请求在飞行，其余请求等它的结果 */
let refreshPromise: Promise<boolean> | null = null

async function refreshAccessToken(): Promise<boolean> {
  if (refreshPromise) return refreshPromise
  refreshPromise = (async () => {
    const refreshToken = tokenStore.getRefresh()
    if (!refreshToken) return false
    try {
      const response = await send('/auth/refresh', {
        method: 'POST',
        body: { refreshToken },
        anonymous: true,
        skipRefresh: true,
      })
      const payload = (await response.json().catch(() => null)) as ApiResponse<LoginVO> | null
      // 刷新令牌已被轮换/登出/过期，只能重新登录
      if (!payload || payload.code !== '0' || !payload.data) return false
      tokenStore.save(payload.data)
      return true
    } catch {
      return false
    }
  })()
  try {
    return await refreshPromise
  } finally {
    refreshPromise = null
  }
}

/**
 * 统一请求入口：
 * 1. 业务成败看 code，非 "0" 一律抛 ApiError；
 * 2. 401 先用 refreshToken 换新令牌并重放一次，换不到才清令牌跳登录；
 * 3. 403 是权限不足，不触发刷新。
 */
export async function request<T>(path: string, options: RequestOptions = {}): Promise<T> {
  let response = await send(path, options)

  if (response.status === 401 && !options.anonymous && !options.skipRefresh) {
    const refreshed = await refreshAccessToken()
    if (refreshed) {
      response = await send(path, options)
    } else {
      notifyUnauthorized()
      throw new ApiError('COMMON_UNAUTHORIZED', '登录已失效，请重新登录', 401)
    }
  }

  const payload = (await response.json().catch(() => null)) as ApiResponse<T> | null
  if (!payload) {
    throw new ApiError('COMMON_SYSTEM_ERROR', '服务响应异常，请稍后重试', response.status)
  }
  if (payload.code !== '0') {
    if (payload.code === 'COMMON_UNAUTHORIZED') notifyUnauthorized()
    throw new ApiError(payload.code, payload.message || '请求失败', response.status)
  }
  return payload.data
}

/** 把错误码翻译成可展示文案，保留服务端 message 的原始语义 */
export function describeError(error: unknown): string {
  if (error instanceof ApiError) return error.message
  if (error instanceof Error) return error.message
  return '网络异常，请稍后重试'
}

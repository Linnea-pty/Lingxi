import { ASSET_BASE_URL } from '@/api/http'

/** 头像等静态资源地址：后端返回相对路径，拼上域名直接给 <img> 用 */
export function assetUrl(path: string | null | undefined): string {
  if (!path) return ''
  if (/^https?:\/\//i.test(path)) return path
  return ASSET_BASE_URL + (path.startsWith('/') ? path : `/${path}`)
}

/** 后端时间可能是 "yyyy-MM-dd HH:mm:ss"，Safari 解析需要把空格换成 T */
function parseTime(value: string): Date | null {
  if (!value) return null
  const normalized = value.includes('T') ? value : value.replace(' ', 'T')
  const date = new Date(normalized)
  return Number.isNaN(date.getTime()) ? null : date
}

const MINUTE = 60 * 1000
const HOUR = 60 * MINUTE
const DAY = 24 * HOUR

/** 列表里的相对时间：刚刚 / N 分钟前 / N 小时前 / N 天前 / 具体日期 */
export function timeAgo(value: string): string {
  const date = parseTime(value)
  if (!date) return value || ''
  const diff = Date.now() - date.getTime()
  if (diff < MINUTE) return '刚刚'
  if (diff < HOUR) return `${Math.floor(diff / MINUTE)} 分钟前`
  if (diff < DAY) return `${Math.floor(diff / HOUR)} 小时前`
  if (diff < 7 * DAY) return `${Math.floor(diff / DAY)} 天前`
  const month = String(date.getMonth() + 1).padStart(2, '0')
  const day = String(date.getDate()).padStart(2, '0')
  return `${date.getFullYear()}-${month}-${day}`
}

/** 完整时间，用于详情页与悬浮提示 */
export function formatDateTime(value: string): string {
  const date = parseTime(value)
  if (!date) return value || ''
  return date.toLocaleString('zh-CN', { hour12: false })
}

/** 昵称首字，作为无头像时的占位 */
export function initialOf(name: string): string {
  return (name || '?').trim().charAt(0).toUpperCase()
}

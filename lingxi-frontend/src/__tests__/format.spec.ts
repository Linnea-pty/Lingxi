import { describe, expect, it } from 'vitest'
import { initialOf, timeAgo } from '../utils/format'

describe('format', () => {
  it('昵称首字大写，空值兜底', () => {
    expect(initialOf('lingxi')).toBe('L')
    expect(initialOf('')).toBe('?')
  })

  it('相对时间按区间切换文案', () => {
    const now = new Date()
    const tenMinutesAgo = new Date(now.getTime() - 10 * 60 * 1000)
    expect(timeAgo(tenMinutesAgo.toISOString())).toBe('10 分钟前')

    const threeHoursAgo = new Date(now.getTime() - 3 * 60 * 60 * 1000)
    expect(timeAgo(threeHoursAgo.toISOString())).toBe('3 小时前')
  })

  it('超过一周展示具体日期', () => {
    const oldDate = new Date(now2019())
    expect(timeAgo(oldDate.toISOString())).toMatch(/^\d{4}-\d{2}-\d{2}$/)
  })
})

function now2019(): number {
  return Date.now() - 400 * 24 * 60 * 60 * 1000
}

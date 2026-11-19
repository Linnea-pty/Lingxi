let sequence = 0

/**
 * 幂等键：一次用户动作生成一个，失败重试复用同一个值。
 * 格式要求 ≤64 位，仅字母、数字、下划线、短横线。
 */
export function newRequestId(): string {
  sequence = (sequence + 1) % 10000
  const stamp = new Date()
    .toISOString()
    .replace(/\D/g, '')
    .slice(0, 14)
  const random = Math.random().toString(36).slice(2, 6)
  return `req-${stamp}-${sequence.toString().padStart(4, '0')}-${random}`
}

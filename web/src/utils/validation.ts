/**
 * RFC 4648 Base32 大写 26 位追溯码校验器。
 * 严格限制字符集为 ^[A-Z2-7]{26}$。
 */
export const PUBLIC_TRACE_ID_PATTERN = /^[A-Z2-7]{26}$/

/**
 * 校验公开追溯码是否符合格式规范。
 */
export function isValidPublicTraceId(value: unknown): boolean {
  if (typeof value !== 'string') return false
  const trimmed = value.trim()
  return PUBLIC_TRACE_ID_PATTERN.test(trimmed)
}

/**
 * 规范化用户输入的追溯码（去首尾空格并转换为大写）。
 */
export function normalizePublicTraceId(value: string): string {
  return value.trim().toUpperCase()
}

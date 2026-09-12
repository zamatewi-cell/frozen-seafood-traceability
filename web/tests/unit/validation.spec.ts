import { describe, it, expect } from 'vitest'
import { isValidPublicTraceId, normalizePublicTraceId, PUBLIC_TRACE_ID_PATTERN } from '@/utils/validation'

describe('PublicTraceId Validation & Normalization', () => {
  const validId = 'WVKJ5Y2C4P4Q6T7XZ2M7K3B2AC'

  it('validates RFC 4648 Base32 26-character uppercase IDs', () => {
    expect(isValidPublicTraceId(validId)).toBe(true)
    expect(PUBLIC_TRACE_ID_PATTERN.test(validId)).toBe(true)
  })

  it('rejects IDs with invalid lengths', () => {
    expect(isValidPublicTraceId(validId.slice(0, 25))).toBe(false) // 25 chars
    expect(isValidPublicTraceId(validId + 'A')).toBe(false) // 27 chars
    expect(isValidPublicTraceId('')).toBe(false)
  })

  it('rejects characters not in Base32 alphabet (0, 1, 8, 9, punctuation, lowercase)', () => {
    // 0, 1, 8, 9 are strictly excluded from RFC 4648 Base32
    expect(isValidPublicTraceId(validId.slice(0, 25) + '0')).toBe(false)
    expect(isValidPublicTraceId(validId.slice(0, 25) + '1')).toBe(false)
    expect(isValidPublicTraceId(validId.slice(0, 25) + '8')).toBe(false)
    expect(isValidPublicTraceId(validId.slice(0, 25) + '9')).toBe(false)
    expect(isValidPublicTraceId(validId.slice(0, 25) + '_')).toBe(false)
    expect(isValidPublicTraceId(validId.toLowerCase())).toBe(false)
  })

  it('rejects null, undefined, non-string types', () => {
    expect(isValidPublicTraceId(null)).toBe(false)
    expect(isValidPublicTraceId(undefined)).toBe(false)
    expect(isValidPublicTraceId(123456789)).toBe(false)
  })

  it('normalizes string by trimming whitespace and uppercase conversion', () => {
    expect(normalizePublicTraceId('  wvkj5y2c4p4q6t7xz2m7k3b2ac  ')).toBe(validId)
    expect(isValidPublicTraceId(normalizePublicTraceId('  ' + validId + '  '))).toBe(true)
  })
})

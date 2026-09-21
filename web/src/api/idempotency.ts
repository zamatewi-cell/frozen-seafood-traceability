/**
 * 写请求幂等键（Idempotency-Key）。
 *
 * 服务端要求 16~128 个字符；同一次业务意图的重试必须复用同一个键，载荷变化时必须换新键，
 * 否则服务端会以 409 IDEMPOTENCY_KEY_REUSED 拒绝。IdempotencyKeyTracker 按载荷指纹维护这一规则。
 * 键只保存在内存中，不写入任何浏览器持久化存储。
 */

export function newIdempotencyKey(): string {
  const cryptoApi = globalThis.crypto
  if (cryptoApi && typeof cryptoApi.randomUUID === 'function') return cryptoApi.randomUUID()
  const bytes = new Uint8Array(16)
  cryptoApi.getRandomValues(bytes)
  return Array.from(bytes, (b) => b.toString(16).padStart(2, '0')).join('')
}

/** 相同载荷复用上一次的键（安全重试），载荷变化时生成新键。 */
export class IdempotencyKeyTracker {
  private fingerprint: string | null = null
  private key: string | null = null

  keyFor(payload: unknown): string {
    const fingerprint = JSON.stringify(payload)
    if (this.key === null || fingerprint !== this.fingerprint) {
      this.fingerprint = fingerprint
      this.key = newIdempotencyKey()
    }
    return this.key
  }

  reset(): void {
    this.fingerprint = null
    this.key = null
  }
}

import { ApiError } from '@/api/client'
import { newIdempotencyKey } from '@/api/idempotency'

interface PendingWrite<P> {
  intent: string
  payload: P
  key: string
}

/**
 * 一次业务意图（例如“发运运输任务 #12 版本 3”）的幂等写请求。
 *
 * 首次执行时固定载荷（含业务时间）与 Idempotency-Key；若结果未知（网络中断 / 超时），
 * 再次执行同一意图会原样重放同一载荷与同一键，由服务端返回原结果而不是重复写入。
 * 其他任何结果（成功或服务端明确拒绝）都会结束该意图，下一次操作使用新的键。
 */
export function useIdempotentWrite() {
  let pending: PendingWrite<unknown> | null = null

  async function run<P, R>(intent: string, buildPayload: () => P, send: (payload: P, key: string) => Promise<R>): Promise<R> {
    if (!pending || pending.intent !== intent) {
      pending = { intent, payload: buildPayload(), key: newIdempotencyKey() }
    }
    const current = pending as PendingWrite<P>
    try {
      const result = await send(current.payload, current.key)
      pending = null
      return result
    } catch (err: unknown) {
      const outcomeUnknown = err instanceof ApiError && (err.status === 0 || err.status === 408)
      if (!outcomeUnknown) pending = null
      throw err
    }
  }

  return { run }
}

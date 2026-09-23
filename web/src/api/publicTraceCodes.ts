import { apiRequest, NotFoundError } from './client'
import type { PublicTraceCode } from '@/types/enterprise'

/**
 * 批次公开追溯码企业端接口：
 * - GET  /api/v1/batches/{batchId}/public-trace-code（批次当前责任组织可读；尚未激活时 404 PUBLIC_TRACE_CODE_NOT_FOUND）
 * - POST /api/v1/batches/{batchId}/public-trace-code/activate（当前责任组织 OPERATOR；已有码时幂等返回同一码）
 * - POST /api/v1/batches/{batchId}/public-trace-code/disable（当前责任组织 OPERATOR；停用为终态）
 * 写请求需要 CSRF 与 Idempotency-Key；服务端是最终权限边界。
 */

const base = (batchId: number) => `/api/v1/batches/${encodeURIComponent(String(batchId))}/public-trace-code`

/** 读取当前公开追溯码；尚未激活时返回 null（与批次不存在的 404 区分）。 */
export async function getPublicTraceCode(batchId: number, signal?: AbortSignal): Promise<PublicTraceCode | null> {
  try {
    return await apiRequest<PublicTraceCode>(base(batchId), { signal })
  } catch (err: unknown) {
    if (err instanceof NotFoundError && err.code === 'PUBLIC_TRACE_CODE_NOT_FOUND') return null
    throw err
  }
}

export function activatePublicTraceCode(batchId: number, idempotencyKey: string): Promise<PublicTraceCode> {
  return apiRequest<PublicTraceCode>(`${base(batchId)}/activate`, {
    method: 'POST',
    headers: { 'Idempotency-Key': idempotencyKey }
  })
}

export function disablePublicTraceCode(batchId: number, idempotencyKey: string): Promise<PublicTraceCode> {
  return apiRequest<PublicTraceCode>(`${base(batchId)}/disable`, {
    method: 'POST',
    headers: { 'Idempotency-Key': idempotencyKey }
  })
}

/** 消费者查询页的站内路径（扫码入口），只使用公开追溯码，不使用批次 ID 或 traceBatchNo。 */
export function consumerTracePath(publicId: string): string {
  return `/trace/${encodeURIComponent(publicId)}`
}

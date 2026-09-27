import { apiRequest } from './client'
import type { Recall, RecallPublicDisposition } from '@/types/enterprise'

/**
 * 模拟召回接口（Phase B PB5；教学演练，不代表真实法定召回）：
 * - POST /api/v1/recalls（被召回批次当前责任组织的 QUALITY_MANAGER；种子批次 FROZEN，或证据充分的 NORMAL）
 * - GET  /api/v1/recalls、GET /api/v1/recalls/{recallId}（发起组织看完整范围；范围批次持有方只看本组织的范围行）
 * - POST /api/v1/recalls/{recallId}/close（发起组织 QUALITY_MANAGER；受控公开处置结论 + 内部总结；批次保留 RECALLED）
 * 写请求需要 CSRF 与 Idempotency-Key。服务端是最终权限边界。
 */

const base = '/api/v1/recalls'
const one = (recallId: number) => `${base}/${encodeURIComponent(String(recallId))}`

export interface StartRecallPayload {
  batchIds: number[]
  reason: string
  alertId?: number
}

export function listRecalls(signal?: AbortSignal): Promise<Recall[]> {
  return apiRequest<Recall[]>(base, { signal })
}

export function getRecall(recallId: number, signal?: AbortSignal): Promise<Recall> {
  return apiRequest<Recall>(one(recallId), { signal })
}

export function startRecall(payload: StartRecallPayload, idempotencyKey: string): Promise<Recall> {
  return apiRequest<Recall>(base, {
    method: 'POST',
    body: payload,
    headers: { 'Idempotency-Key': idempotencyKey }
  })
}

export function closeRecall(recallId: number, publicDisposition: RecallPublicDisposition, resultSummary: string,
  idempotencyKey: string): Promise<Recall> {
  return apiRequest<Recall>(`${one(recallId)}/close`, {
    method: 'POST',
    body: { publicDisposition, resultSummary },
    headers: { 'Idempotency-Key': idempotencyKey }
  })
}

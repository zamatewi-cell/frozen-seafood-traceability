import { apiRequest } from './client'
import type { BatchRiskTransition } from '@/types/enterprise'

/**
 * 批次风险状态接口（Phase B PB1：人工风险冻结 / 解除冻结）：
 * - GET  /api/v1/batches/{batchId}/risk-transitions（当前责任组织与平台只读：完整历史；历史参与组织：仅本组织登记的转换）
 * - POST /api/v1/batches/{batchId}/risk/freeze（当前责任组织 QUALITY_MANAGER；NORMAL → FROZEN）
 * - POST /api/v1/batches/{batchId}/risk/release（当前责任组织 QUALITY_MANAGER；FROZEN → NORMAL）
 * 写请求需要 CSRF 与 Idempotency-Key，只发送原因；目标状态由路径决定，转换时间由服务端生成。服务端是最终权限边界。
 */

const base = (batchId: number) => `/api/v1/batches/${encodeURIComponent(String(batchId))}`

export function listRiskTransitions(batchId: number, signal?: AbortSignal): Promise<BatchRiskTransition[]> {
  return apiRequest<BatchRiskTransition[]>(`${base(batchId)}/risk-transitions`, { signal })
}

export function freezeBatch(batchId: number, reason: string, idempotencyKey: string): Promise<BatchRiskTransition> {
  return apiRequest<BatchRiskTransition>(`${base(batchId)}/risk/freeze`, {
    method: 'POST',
    body: { reason },
    headers: { 'Idempotency-Key': idempotencyKey }
  })
}

export function releaseBatch(batchId: number, reason: string, idempotencyKey: string): Promise<BatchRiskTransition> {
  return apiRequest<BatchRiskTransition>(`${base(batchId)}/risk/release`, {
    method: 'POST',
    body: { reason },
    headers: { 'Idempotency-Key': idempotencyKey }
  })
}

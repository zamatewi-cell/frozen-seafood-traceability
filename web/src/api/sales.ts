import { apiRequest } from './client'
import type { CreateSalePayload, Sale } from '@/types/enterprise'

/**
 * 终端销售接口：
 * - GET  /api/v1/batches/{batchId}/sales（批次当前责任组织可读）
 * - POST /api/v1/batches/{batchId}/sales（当前责任 RETAILER 的 OPERATOR；需 CSRF + Idempotency-Key）
 * 剩余量、首次销售锁定与售罄关闭全部由服务端决定，浏览器只发送白名单字段。
 */

export function listSales(batchId: number, signal?: AbortSignal): Promise<Sale[]> {
  return apiRequest<Sale[]>(`/api/v1/batches/${encodeURIComponent(String(batchId))}/sales`, { signal })
}

export function createSale(batchId: number, payload: CreateSalePayload, idempotencyKey: string): Promise<Sale> {
  return apiRequest<Sale>(`/api/v1/batches/${encodeURIComponent(String(batchId))}/sales`, {
    method: 'POST',
    body: {
      siteId: payload.siteId,
      quantity: payload.quantity,
      occurredAt: payload.occurredAt
    },
    headers: { 'Idempotency-Key': idempotencyKey }
  })
}

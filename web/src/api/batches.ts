import { apiRequest, apiRequestPage } from './client'
import type { Batch, BatchListQuery, CreateSourceBatchRequest, TraceEvent } from '@/types/enterprise'
import type { PagedResult } from '@/types/api'

/**
 * 企业端批次接口：
 * - GET  /api/v1/batches、GET /api/v1/batches/{batchId}（服务端按当前责任组织隔离数据范围）
 * - POST /api/v1/batches（仅来源组织 OPERATOR 创建来源批次草稿；需 CSRF + Idempotency-Key）
 * - POST /api/v1/batches/{batchId}/submit（提交激活，服务端同事务自动生成 SOURCE 事件；需 CSRF）
 * - GET  /api/v1/batches/{batchId}/events（追溯事件列表）
 */

export function listBatches(query: BatchListQuery, signal?: AbortSignal): Promise<PagedResult<Batch>> {
  return apiRequestPage<Batch>('/api/v1/batches', {
    query: {
      page: query.page,
      size: query.size,
      flowStatus: query.flowStatus,
      riskStatus: query.riskStatus
    },
    signal
  })
}

export function getBatch(batchId: number, signal?: AbortSignal): Promise<Batch> {
  return apiRequest<Batch>(`/api/v1/batches/${encodeURIComponent(String(batchId))}`, { signal })
}

/**
 * 创建来源批次草稿。请求体只包含 CreateSourceBatchRequest 声明的字段：
 * batchType、traceBatchNo、orgId、状态等服务端字段绝不从浏览器发送。
 */
export function createSourceBatch(request: CreateSourceBatchRequest, idempotencyKey: string): Promise<Batch> {
  return apiRequest<Batch>('/api/v1/batches', {
    method: 'POST',
    body: toCreatePayload(request),
    headers: { 'Idempotency-Key': idempotencyKey }
  })
}

export function submitBatch(batchId: number, version: number): Promise<Batch> {
  return apiRequest<Batch>(`/api/v1/batches/${encodeURIComponent(String(batchId))}/submit`, {
    method: 'POST',
    body: { version }
  })
}

export function listBatchEvents(batchId: number, signal?: AbortSignal): Promise<TraceEvent[]> {
  return apiRequest<TraceEvent[]>(`/api/v1/batches/${encodeURIComponent(String(batchId))}/events`, { signal })
}

/** 白名单构造创建载荷，省略未填写的可选字段。 */
export function toCreatePayload(request: CreateSourceBatchRequest): CreateSourceBatchRequest {
  const payload: CreateSourceBatchRequest = {
    productId: request.productId,
    quantity: request.quantity,
    unitCode: request.unitCode,
    originType: request.originType,
    originText: request.originText
  }
  if (request.externalBatchNo) payload.externalBatchNo = request.externalBatchNo
  if (request.productionDate) payload.productionDate = request.productionDate
  if (request.captureDate) payload.captureDate = request.captureDate
  if (request.freezeDate) payload.freezeDate = request.freezeDate
  if (request.shelfLifeDays !== undefined) payload.shelfLifeDays = request.shelfLifeDays
  return payload
}

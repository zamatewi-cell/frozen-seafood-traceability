import { apiRequest, apiRequestPage } from './client'
import type { PagedResult } from '@/types/api'
import type { BatchOperation, BatchOperationItemRequest, SupportedOperationType } from '@/types/enterprise'

/**
 * 批次操作（物料转换）接口（/api/v1/batch-operations）。写请求由 client 统一附带 CSRF；
 * 创建与提交必须携带 Idempotency-Key。当前 Slice 只执行 PROCESS 与 SPLIT，且恰好一个 INPUT；
 * OUTPUT 批次由服务端生成，浏览器从不发送 OUTPUT 的 batchId。
 */

function path(operationId: number, suffix = ''): string {
  return `/api/v1/batch-operations/${encodeURIComponent(String(operationId))}${suffix}`
}

export interface CreateBatchOperationPayload {
  operationType: SupportedOperationType
  occurredAt: string
  note?: string
  items: BatchOperationItemRequest[]
}

export function createBatchOperation(payload: CreateBatchOperationPayload, idempotencyKey: string): Promise<BatchOperation> {
  return apiRequest<BatchOperation>('/api/v1/batch-operations', {
    method: 'POST',
    body: payload,
    headers: { 'Idempotency-Key': idempotencyKey }
  })
}

export function submitBatchOperation(operationId: number, version: number, idempotencyKey: string): Promise<BatchOperation> {
  return apiRequest<BatchOperation>(path(operationId, '/submit'), {
    method: 'POST',
    body: { version },
    headers: { 'Idempotency-Key': idempotencyKey }
  })
}

export function getBatchOperation(operationId: number, signal?: AbortSignal): Promise<BatchOperation> {
  return apiRequest<BatchOperation>(path(operationId), { signal })
}

export function listBatchOperations(batchId: number, signal?: AbortSignal): Promise<PagedResult<BatchOperation>> {
  return apiRequestPage<BatchOperation>('/api/v1/batch-operations', {
    query: { batchId, page: 1, size: 20 },
    signal
  })
}

export function deleteBatchOperationDraft(operationId: number, expectedVersion: number): Promise<void> {
  return apiRequest<void>(path(operationId), {
    method: 'DELETE',
    query: { expectedVersion }
  })
}

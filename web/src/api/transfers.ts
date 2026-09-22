import { apiRequest, apiRequestPage } from './client'
import type { PagedResult } from '@/types/api'
import type { Transfer, TransferListQuery } from '@/types/enterprise'

/**
 * 企业间整批交接接口（/api/v1/transfers）。写请求由 client 统一附带 CSRF；
 * 创建 / 提交 / 接受 / 拒收必须携带 Idempotency-Key。
 */

function path(transferId: number, suffix = ''): string {
  return `/api/v1/transfers/${encodeURIComponent(String(transferId))}${suffix}`
}

export function listTransfers(query: TransferListQuery, signal?: AbortSignal): Promise<PagedResult<Transfer>> {
  return apiRequestPage<Transfer>('/api/v1/transfers', {
    query: {
      direction: query.direction,
      status: query.status,
      batchId: query.batchId,
      page: query.page,
      size: query.size
    },
    signal
  })
}

export interface CreateTransferPayload {
  batchId: number
  receiverOrgId: number
}

export function createTransfer(payload: CreateTransferPayload, idempotencyKey: string): Promise<Transfer> {
  return apiRequest<Transfer>('/api/v1/transfers', {
    method: 'POST',
    body: { batchId: payload.batchId, receiverOrgId: payload.receiverOrgId },
    headers: { 'Idempotency-Key': idempotencyKey }
  })
}

export function submitTransfer(transferId: number, expectedVersion: number, idempotencyKey: string): Promise<Transfer> {
  return apiRequest<Transfer>(path(transferId, '/submit'), {
    method: 'POST',
    body: { expectedVersion },
    headers: { 'Idempotency-Key': idempotencyKey }
  })
}

export interface AcceptTransferPayload {
  receivedQuantity: number
  unitCode: string
  occurredAt: string
  differenceReason?: string
  expectedVersion: number
}

export function acceptTransfer(transferId: number, payload: AcceptTransferPayload, idempotencyKey: string): Promise<Transfer> {
  return apiRequest<Transfer>(path(transferId, '/accept'), {
    method: 'POST',
    body: payload,
    headers: { 'Idempotency-Key': idempotencyKey }
  })
}

export interface RejectTransferPayload {
  reason: string
  occurredAt: string
  expectedVersion: number
}

export function rejectTransfer(transferId: number, payload: RejectTransferPayload, idempotencyKey: string): Promise<Transfer> {
  return apiRequest<Transfer>(path(transferId, '/reject'), {
    method: 'POST',
    body: payload,
    headers: { 'Idempotency-Key': idempotencyKey }
  })
}

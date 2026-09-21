import { apiRequest, apiRequestPage } from './client'
import type { Batch, BatchListQuery } from '@/types/enterprise'
import type { PagedResult } from '@/types/api'

/**
 * 企业端批次只读接口：GET /api/v1/batches 与 GET /api/v1/batches/{batchId}。
 * 服务端按当前责任组织隔离数据范围。
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

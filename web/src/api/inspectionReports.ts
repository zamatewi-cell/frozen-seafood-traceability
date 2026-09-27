import { apiRequest } from './client'
import type { InspectionConclusion, InspectionReport } from '@/types/enterprise'

/**
 * 批次检验报告接口（Phase B PB4）：
 * - GET  /api/v1/batches/{batchId}/inspection-reports（当前责任组织与平台只读看全部；隔离接收方 / 提交过报告的组织只看本组织提交的）
 * - POST /api/v1/batches/{batchId}/inspection-reports（QUALITY_MANAGER；当前责任组织或该批次隔离交接的接收方）
 * 报告只是证据：不自动放行、冻结或召回，也不核验检验机构真实性。写请求需要 CSRF 与 Idempotency-Key。
 */

const path = (batchId: number) => `/api/v1/batches/${encodeURIComponent(String(batchId))}/inspection-reports`

export interface SubmitInspectionPayload {
  reportNo: string
  institutionName: string
  inspectedAt: string
  itemsSummary: string
  conclusion: InspectionConclusion
  dataSource: 'MANUAL' | 'SIMULATED'
  alertId?: number
}

export function listInspectionReports(batchId: number, signal?: AbortSignal): Promise<InspectionReport[]> {
  return apiRequest<InspectionReport[]>(path(batchId), { signal })
}

export function submitInspectionReport(batchId: number, payload: SubmitInspectionPayload, idempotencyKey: string): Promise<InspectionReport> {
  return apiRequest<InspectionReport>(path(batchId), {
    method: 'POST',
    body: payload,
    headers: { 'Idempotency-Key': idempotencyKey }
  })
}

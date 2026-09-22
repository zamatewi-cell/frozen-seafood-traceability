import type { ProblemDetails } from '@/types/trace'

/**
 * 质检 API（质量管理员）。
 */

export interface QualityInspection {
  id: number
  inspectionNo: string
  batchId: number
  orgId: number
  inspectionType: string
  relatedTransferId: number | null
  inspectorId: number | null
  inspectorName: string | null
  result: string
  summary: string | null
  checklistJson: string | null
  checkedAt: string | null
  createdAt: string
}

interface Envelope<T> {
  data?: T
  detail?: string
}

async function fetchCsrf(): Promise<string> {
  const res = await fetch('/api/v1/auth/csrf', { credentials: 'include' })
  const payload = (await res.json()) as Envelope<{ token: string }>
  const token = payload.data?.token
  if (!token) throw new Error('CSRF 获取失败')
  return token
}

async function parseEnvelope<T>(res: Response): Promise<T> {
  const payload = (await res.json()) as Envelope<T>
  if (!res.ok || payload.data === undefined) {
    const problem = payload as unknown as ProblemDetails
    throw new Error(problem?.detail || payload.detail || `请求失败 (${res.status})`)
  }
  return payload.data
}

export async function listInspections(batchId: number): Promise<QualityInspection[]> {
  const res = await fetch(`/api/v1/batches/${batchId}/inspections`, { credentials: 'include' })
  return parseEnvelope(res)
}

export interface InspectionCreatePayload {
  inspectionType: string
  relatedTransferId?: number
  summary?: string
}

export async function createInspection(
  batchId: number,
  payload: InspectionCreatePayload
): Promise<QualityInspection> {
  const csrf = await fetchCsrf()
  const res = await fetch(`/api/v1/batches/${batchId}/inspections`, {
    method: 'POST',
    credentials: 'include',
    headers: { 'Content-Type': 'application/json', 'X-CSRF-TOKEN': csrf },
    body: JSON.stringify(payload)
  })
  return parseEnvelope<QualityInspection>(res)
}

export interface InspectionResultPayload {
  result: 'PASS' | 'FAIL'
  summary?: string
}

export async function submitInspectionResult(
  inspectionId: number,
  payload: InspectionResultPayload
): Promise<QualityInspection> {
  const csrf = await fetchCsrf()
  const res = await fetch(`/api/v1/inspections/${inspectionId}/result`, {
    method: 'POST',
    credentials: 'include',
    headers: { 'Content-Type': 'application/json', 'X-CSRF-TOKEN': csrf },
    body: JSON.stringify(payload)
  })
  return parseEnvelope<QualityInspection>(res)
}
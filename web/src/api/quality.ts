import type { ProblemDetails } from '@/types/trace'

/**
 * 质检 API（质量管理员 + 操作员提交质检审核）。
 */

export interface QualityInspection {
  id: number
  inspectionNo: string
  batchId: number
  orgId: number
  inspectionType: string
  inspectionStage: string | null
  relatedOrderId: number | null
  relatedTransferId: number | null
  inspectorId: number | null
  inspectorName: string | null
  result: string
  summary: string | null
  checklistJson: string | null
  checklistPassedAt: string | null
  checkedAt: string | null
  createdAt: string
}

export interface ChecklistTemplateItem {
  id: number
  stageCode: string
  orgType: string
  itemName: string
  itemDesc: string | null
  sortOrder: number
  isRequired: number
}

export interface ChecklistItemState {
  itemName: string
  itemDesc: string | null
  passed: boolean
  checkedBy: string | null
  checkedAt: string | null
  remark: string | null
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

// ==================== 新:环节质检流程 ====================

export async function getChecklistTemplate(stageCode: string): Promise<ChecklistTemplateItem[]> {
  const res = await fetch(`/api/v1/quality/checklist-template/${stageCode}`, { credentials: 'include' })
  return parseEnvelope(res)
}

export async function submitQualityReview(orderId: number): Promise<QualityInspection[]> {
  const csrf = await fetchCsrf()
  const res = await fetch(`/api/v1/orders/purchase/${orderId}/submit-quality-review`, {
    method: 'POST',
    credentials: 'include',
    headers: { 'Content-Type': 'application/json', 'X-CSRF-TOKEN': csrf }
  })
  return parseEnvelope<QualityInspection[]>(res)
}

export async function listPendingInspections(): Promise<QualityInspection[]> {
  const res = await fetch('/api/v1/quality/pending-inspections', { credentials: 'include' })
  return parseEnvelope(res)
}

export async function listInspectionsByOrder(orderId: number): Promise<QualityInspection[]> {
  const res = await fetch(`/api/v1/orders/purchase/${orderId}/inspections`, { credentials: 'include' })
  return parseEnvelope(res)
}

export async function updateChecklistItem(
  inspectionId: number,
  payload: { itemName: string; passed: boolean; remark?: string }
): Promise<QualityInspection> {
  const csrf = await fetchCsrf()
  const res = await fetch(`/api/v1/inspections/${inspectionId}/checklist-item`, {
    method: 'POST',
    credentials: 'include',
    headers: { 'Content-Type': 'application/json', 'X-CSRF-TOKEN': csrf },
    body: JSON.stringify(payload)
  })
  return parseEnvelope<QualityInspection>(res)
}

export async function passInspection(inspectionId: number): Promise<QualityInspection> {
  const csrf = await fetchCsrf()
  const res = await fetch(`/api/v1/inspections/${inspectionId}/pass`, {
    method: 'POST',
    credentials: 'include',
    headers: { 'Content-Type': 'application/json', 'X-CSRF-TOKEN': csrf }
  })
  return parseEnvelope<QualityInspection>(res)
}

export function parseChecklist(json: string | null): ChecklistItemState[] {
  if (!json) return []
  try {
    return JSON.parse(json) as ChecklistItemState[]
  } catch {
    return []
  }
}

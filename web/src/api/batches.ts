import type { ProblemDetails } from '@/types/trace'

/**
 * 批次与交接 API（企业操作员）。
 */

export interface BatchItem {
  id: number
  orgId: number
  productId: number
  batchNo: string
  batchType: string
  quantity: number
  unitCode: string
  originType: string
  originText: string
  productionDate: string | null
  captureDate: string | null
  freezeDate: string | null
  shelfLifeDays: number | null
  status: string
  version: number
  createdAt: string
}

export interface Transfer {
  id: number
  transferNo: string
  batchId: number
  senderOrgId: number
  receiverOrgId: number
  quantity: number
  unitCode: string
  status: string
  shippedAt: string | null
  receivedAt: string | null
  receivedQuantity: number | null
  differenceReason: string | null
  rejectionReason: string | null
  version: number
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

function idemKey(): string {
  return `k-${Date.now()}-${Math.random().toString(36).slice(2, 12)}`
}

export async function listBatches(status?: string): Promise<BatchItem[]> {
  const qs = status ? `?status=${status}&size=100` : '?size=100'
  const res = await fetch(`/api/v1/batches${qs}`, { credentials: 'include' })
  return parseEnvelope(res)
}

export interface BatchCreatePayload {
  batchNo: string
  productId: number
  batchType: string
  quantity: number
  unitCode: string
  originType: string
  originText: string
  productionDate?: string
  captureDate?: string
  freezeDate?: string
  shelfLifeDays?: number
}

export async function createBatch(payload: BatchCreatePayload): Promise<BatchItem> {
  const csrf = await fetchCsrf()
  const res = await fetch('/api/v1/batches', {
    method: 'POST',
    credentials: 'include',
    headers: {
      'Content-Type': 'application/json',
      'X-CSRF-TOKEN': csrf,
      'Idempotency-Key': idemKey()
    },
    body: JSON.stringify(payload)
  })
  return parseEnvelope<BatchItem>(res)
}

export async function submitBatch(batchId: number, version: number): Promise<BatchItem> {
  const csrf = await fetchCsrf()
  const res = await fetch(`/api/v1/batches/${batchId}/submit`, {
    method: 'POST',
    credentials: 'include',
    headers: { 'Content-Type': 'application/json', 'X-CSRF-TOKEN': csrf },
    body: JSON.stringify({ version })
  })
  return parseEnvelope<BatchItem>(res)
}

// 捕捞船长直接入库(自捕自产)
export interface DirectStockInPayload {
  productId: number
  quantity: number
  batchNo?: string
  originText?: string
  captureDate?: string
}

export async function directStockIn(payload: DirectStockInPayload): Promise<BatchItem> {
  const csrf = await fetchCsrf()
  const res = await fetch('/api/v1/batches/direct-stock-in', {
    method: 'POST',
    credentials: 'include',
    headers: {
      'Content-Type': 'application/json',
      'X-CSRF-TOKEN': csrf,
      'Idempotency-Key': idemKey()
    },
    body: JSON.stringify(payload)
  })
  return parseEnvelope<BatchItem>(res)
}

// 加工厂加工:消耗原料批次 → 产出成品批次
export interface ProcessPayload {
  sourceBatchId: number
  consumedQuantity: number
  outputProductId: number
  outputQuantity: number
}

export async function processMaterials(payload: ProcessPayload): Promise<BatchItem> {
  const csrf = await fetchCsrf()
  const res = await fetch('/api/v1/batches/process', {
    method: 'POST',
    credentials: 'include',
    headers: {
      'Content-Type': 'application/json',
      'X-CSRF-TOKEN': csrf,
      'Idempotency-Key': idemKey()
    },
    body: JSON.stringify(payload)
  })
  return parseEnvelope<BatchItem>(res)
}

// 批发分拣分装:消耗上游批次 → 产出同产品 DISTRIBUTION 批次
export interface RepackPayload {
  sourceBatchId: number
  consumedQuantity: number
  outputQuantity: number
}

export async function repackBatch(payload: RepackPayload): Promise<BatchItem> {
  const csrf = await fetchCsrf()
  const res = await fetch('/api/v1/batches/repack', {
    method: 'POST',
    credentials: 'include',
    headers: {
      'Content-Type': 'application/json',
      'X-CSRF-TOKEN': csrf,
      'Idempotency-Key': idemKey()
    },
    body: JSON.stringify(payload)
  })
  return parseEnvelope<BatchItem>(res)
}

export async function listTransfers(direction?: string, status?: string): Promise<Transfer[]> {
  const params = new URLSearchParams()
  params.set('page', '1')
  params.set('size', '100')
  if (direction) params.set('direction', direction)
  if (status) params.set('status', status)
  const res = await fetch(`/api/v1/transfers?${params.toString()}`, { credentials: 'include' })
  return parseEnvelope(res)
}

export async function createTransfer(
  batchId: number,
  receiverOrgId: number
): Promise<Transfer> {
  const csrf = await fetchCsrf()
  const res = await fetch('/api/v1/transfers', {
    method: 'POST',
    credentials: 'include',
    headers: {
      'Content-Type': 'application/json',
      'X-CSRF-TOKEN': csrf,
      'Idempotency-Key': idemKey()
    },
    body: JSON.stringify({ batchId, receiverOrgId })
  })
  return parseEnvelope<Transfer>(res)
}

export async function submitTransfer(
  transferId: number,
  expectedVersion: number
): Promise<Transfer> {
  const csrf = await fetchCsrf()
  const res = await fetch(`/api/v1/transfers/${transferId}/submit`, {
    method: 'POST',
    credentials: 'include',
    headers: {
      'Content-Type': 'application/json',
      'X-CSRF-TOKEN': csrf,
      'Idempotency-Key': idemKey()
    },
    body: JSON.stringify({ shippedAt: new Date().toISOString(), expectedVersion })
  })
  return parseEnvelope<Transfer>(res)
}

export interface AcceptPayload {
  receivedQuantity: number
  unitCode: string
  occurredAt: string
  differenceReason?: string
  expectedVersion: number
}

export async function acceptTransfer(
  transferId: number,
  payload: AcceptPayload
): Promise<Transfer> {
  const csrf = await fetchCsrf()
  const res = await fetch(`/api/v1/transfers/${transferId}/accept`, {
    method: 'POST',
    credentials: 'include',
    headers: {
      'Content-Type': 'application/json',
      'X-CSRF-TOKEN': csrf,
      'Idempotency-Key': idemKey()
    },
    body: JSON.stringify(payload)
  })
  return parseEnvelope<Transfer>(res)
}

/**
 * 中间环节补充溯源事件（append-only）。
 * 示例：捕捞船长接到加工厂订单后补记「正在捕捞」，加工/分装/质检补记对应环节。
 */
export interface TraceEventPayload {
  eventType: string
  occurredAt: string
  siteId?: number | null
  dataSource: string
  summary: string
}

export async function supplementTraceEvent(
  batchId: number,
  payload: TraceEventPayload
): Promise<{ id: number; eventType: string }> {
  const csrf = await fetchCsrf()
  const res = await fetch(`/api/v1/batches/${batchId}/events`, {
    method: 'POST',
    credentials: 'include',
    headers: {
      'Content-Type': 'application/json',
      'X-CSRF-TOKEN': csrf,
      'Idempotency-Key': idemKey()
    },
    body: JSON.stringify({
      ...payload,
      occurredAt: payload.occurredAt || new Date().toISOString(),
      dataSource: payload.dataSource || 'MANUAL'
    })
  })
  return parseEnvelope(res)
}

/**
 * 将物理批次聚合绑定到某个公开溯源码（一码可聚合多批）。
 */
export async function bindBatchToPublicCode(
  publicId: string,
  batchId: number,
  bindRole?: string
): Promise<{ id: number; publicId: string; status: string }> {
  const csrf = await fetchCsrf()
  const res = await fetch('/api/v1/public-trace-codes/bind-batch', {
    method: 'POST',
    credentials: 'include',
    headers: {
      'Content-Type': 'application/json',
      'X-CSRF-TOKEN': csrf
    },
    body: JSON.stringify({ publicId, batchId, bindRole })
  })
  return parseEnvelope(res)
}
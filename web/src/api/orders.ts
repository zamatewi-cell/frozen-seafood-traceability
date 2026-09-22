import type { ProblemDetails } from '@/types/trace'

/**
 * 多角色订单 API（采购进货单 / 销售订单）。
 * 依赖服务端会话，写操作需带 CSRF。
 */

export interface OrderItem {
  id: number
  productId: number
  productName: string
  quantity: number
  unitCode: string
  unitPrice: number
  rowAmount: number
}

export interface PurchaseOrder {
  id: number
  orderNo: string
  buyerOrgId: number
  sellerOrgId: number
  orderType: string
  status: string
  handlingStatus: string | null
  orderedAt: string
  expectedDeliveryAt: string | null
  note: string | null
  buyerContactName: string | null
  buyerContactPhone: string | null
  buyerContactAddress: string | null
  approvedBy: number | null
  approvedAt: string | null
  rejectReason: string | null
  receiptBatchId: number | null
  traceCodeId: number | null
  publicTraceId: string | null
  cancelRequestRole: string | null
  cancelRequestReason: string | null
  cancelRequestStatus: string | null
  amountTotal: number
  currencyCode: string
  items: OrderItem[]
}

export interface SalesOrder {
  id: number
  orderNo: string
  sellerOrgId: number
  customerName: string
  customerPhone: string | null
  deliveryAddress: string
  status: string
  traceCodeId: number | null
  packedPackageNo: string | null
  placedAt: string
  deliveredAt: string | null
  note: string | null
  amountTotal: number
  currencyCode: string
  items: OrderItem[]
}

interface Envelope<T> {
  code?: string
  data?: T
  detail?: string
  requestId?: string
  status?: number
  title?: string
  fieldErrors?: unknown[]
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

export async function listPurchaseOrders(): Promise<PurchaseOrder[]> {
  const res = await fetch('/api/v1/orders/purchase', { credentials: 'include' })
  return parseEnvelope(res)
}

export async function listSalesOrders(): Promise<SalesOrder[]> {
  const res = await fetch('/api/v1/orders/sales', { credentials: 'include' })
  return parseEnvelope(res)
}

export interface PurchaseCreatePayload {
  sellerOrgId: number
  orderType: string
  note?: string
  buyerContactName?: string
  buyerContactPhone?: string
  buyerContactAddress?: string
  items: { productId: number; quantity: number; unitPrice: number }[]
}

export async function createPurchaseOrder(payload: PurchaseCreatePayload): Promise<PurchaseOrder> {
  const csrf = await fetchCsrf()
  const res = await fetch('/api/v1/orders/purchase', {
    method: 'POST',
    credentials: 'include',
    headers: {
      'Content-Type': 'application/json',
      'X-CSRF-TOKEN': csrf
    },
    body: JSON.stringify(payload)
  })
  return parseEnvelope<PurchaseOrder>(res)
}

export interface SalesCreatePayload {
  customerName: string
  customerPhone?: string
  deliveryAddress: string
  note?: string
  items: { productId: number; quantity: number; unitPrice: number }[]
}

export async function createSalesOrder(payload: SalesCreatePayload): Promise<SalesOrder> {
  const csrf = await fetchCsrf()
  const res = await fetch('/api/v1/orders/sales', {
    method: 'POST',
    credentials: 'include',
    headers: {
      'Content-Type': 'application/json',
      'X-CSRF-TOKEN': csrf
    },
    body: JSON.stringify(payload)
  })
  return parseEnvelope<SalesOrder>(res)
}

async function postWithCsrf<T>(url: string, body?: unknown): Promise<T> {
  const csrf = await fetchCsrf()
  const res = await fetch(url, {
    method: 'POST',
    credentials: 'include',
    headers: {
      'Content-Type': 'application/json',
      'X-CSRF-TOKEN': csrf
    },
    body: body === undefined ? undefined : JSON.stringify(body)
  })
  return parseEnvelope<T>(res)
}

export async function approvePurchaseOrder(orderId: number): Promise<PurchaseOrder> {
  return postWithCsrf<PurchaseOrder>(`/api/v1/orders/purchase/${orderId}/approve`)
}

export async function rejectPurchaseOrder(
  orderId: number,
  reason: string
): Promise<PurchaseOrder> {
  return postWithCsrf<PurchaseOrder>(`/api/v1/orders/purchase/${orderId}/reject`, { reason })
}

export async function schedulePurchaseOrder(orderId: number): Promise<PurchaseOrder> {
  return postWithCsrf<PurchaseOrder>(`/api/v1/orders/purchase/${orderId}/schedule`)
}

export async function completePurchaseDelivery(orderId: number): Promise<PurchaseOrder> {
  return postWithCsrf<PurchaseOrder>(`/api/v1/orders/purchase/${orderId}/complete-delivery`)
}

export async function receivePurchaseOrder(orderId: number): Promise<PurchaseOrder> {
  return postWithCsrf<PurchaseOrder>(`/api/v1/orders/purchase/${orderId}/receive`)
}

export async function requestCancelOrder(orderId: number, reason: string): Promise<PurchaseOrder> {
  return postWithCsrf<PurchaseOrder>(`/api/v1/orders/purchase/${orderId}/cancel-request`, { reason })
}

export async function approveCancelRequest(orderId: number): Promise<PurchaseOrder> {
  return postWithCsrf<PurchaseOrder>(`/api/v1/orders/purchase/${orderId}/cancel-approve`)
}

export async function rejectCancelRequest(orderId: number): Promise<PurchaseOrder> {
  return postWithCsrf<PurchaseOrder>(`/api/v1/orders/purchase/${orderId}/cancel-reject`)
}

export interface OrderNote {
  id: number
  orderId: number
  orderType: string
  noteText: string
  statusAt: string
  isTerminalVisible: number
  orgId: number
  createdBy: number
  createdAt: string
}

export async function addOrderNote(
  orderType: 'PURCHASE' | 'SALES',
  orderId: number,
  payload: { noteText: string; statusAt: string }
): Promise<OrderNote> {
  return postWithCsrf<OrderNote>(
    `/api/v1/orders/${orderType}/${orderId}/notes`,
    { ...payload, isTerminalVisible: 1 }
  )
}

export async function listOrderNotes(
  orderType: 'PURCHASE' | 'SALES',
  orderId: number
): Promise<OrderNote[]> {
  const res = await fetch(`/api/v1/orders/${orderType}/${orderId}/notes`, { credentials: 'include' })
  return parseEnvelope(res)
}

export interface BatchAllocation {
  id: number
  orderId: number
  batchId: number
  batchNo: string
  productName: string | null
  allocatedQuantity: number
  unitCode: string
  allocationOrder: number
  orgId: number
  allocatedBy: number
  allocatedAt: string
}

export async function allocateBatch(
  orderId: number,
  payload: { batchId: number; allocatedQuantity: number }
): Promise<BatchAllocation> {
  return postWithCsrf<BatchAllocation>(
    `/api/v1/purchase-orders/${orderId}/allocations`,
    payload
  )
}

export async function listAllocations(orderId: number): Promise<BatchAllocation[]> {
  const res = await fetch(`/api/v1/purchase-orders/${orderId}/allocations`, { credentials: 'include' })
  return parseEnvelope(res)
}

export async function removeAllocation(orderId: number, allocId: number): Promise<void> {
  const csrf = await fetchCsrf()
  await fetch(`/api/v1/purchase-orders/${orderId}/allocations/${allocId}`, {
    method: 'DELETE',
    credentials: 'include',
    headers: { 'X-CSRF-TOKEN': csrf }
  })
}

export async function updateSalesStatus(
  orderId: number,
  status: string
): Promise<SalesOrder> {
  return postWithCsrf<SalesOrder>(`/api/v1/orders/sales/${orderId}/status`, { status })
}